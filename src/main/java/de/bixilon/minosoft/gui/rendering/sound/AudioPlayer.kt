/*
 * Minosoft
 * Copyright (C) 2020-2025 Moritz Zwerger
 * Copyright (C) 2026 Jacob Repp
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * This software is not affiliated with Mojang AB, the original developer of Minecraft.
 */

package de.bixilon.minosoft.gui.rendering.sound

import de.bixilon.kmath.vec.vec3.d.Vec3d
import de.bixilon.kmath.vec.vec3.f.Vec3f
import de.bixilon.kutil.collections.CollectionUtil.synchronizedListOf
import de.bixilon.kutil.collections.CollectionUtil.toSynchronizedList
import de.bixilon.kutil.concurrent.queue.Queue
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.kutil.observer.DataObserver.Companion.observe
import de.bixilon.kutil.time.TimeUtil.sleep
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.data.world.audio.AbstractAudioPlayer
import de.bixilon.minosoft.gui.rendering.Rendering
import de.bixilon.minosoft.gui.rendering.camera.CameraDefinition.CAMERA_UP_VEC3
import de.bixilon.minosoft.gui.rendering.events.CameraPositionChangeEvent
import de.bixilon.minosoft.gui.rendering.sound.sounds.Sound
import de.bixilon.minosoft.gui.rendering.util.vec.vec3.Vec3dUtil
import de.bixilon.minosoft.modding.event.listener.CallbackEventListener.Companion.listen
import de.bixilon.minosoft.modding.loader.fabric.FabricSoundEventContext
import de.bixilon.minosoft.modding.loader.fabric.FabricSoundEvents
import de.bixilon.minosoft.protocol.network.session.play.PlaySession
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType
import org.lwjgl.openal.AL
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.*
import org.lwjgl.openal.EXTThreadLocalContext.alcSetThreadContext
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds


class AudioPlayer(
    val session: PlaySession,
    val rendering: Rendering,
) : AbstractAudioPlayer {
    private val profile = session.profiles.audio
    private val soundManager = SoundManager(session)
    @Volatile
    var initialized = false
        private set

    private var device = -1L
    private var context = -1L

    private val queue = Queue()
    private lateinit var listener: SoundListener
    private val sources: MutableList<SoundSource> = synchronizedListOf()

    var availableSources: Int = 0
        private set

    val sourcesCount: Int
        get() = sources.size

    @Volatile
    var appliedMasterVolume: Float = profile.volume.master
        private set

    private val requestedSoundCounter = AtomicLong()
    private val resolvedSoundCounter = AtomicLong()
    private val unresolvedSoundCounter = AtomicLong()
    private val startedSoundCounter = AtomicLong()
    private val distanceRejectedSoundCounter = AtomicLong()
    private val missingBufferSoundCounter = AtomicLong()

    val requestedSounds: Long
        get() = requestedSoundCounter.get()
    val resolvedSounds: Long
        get() = resolvedSoundCounter.get()
    val unresolvedSounds: Long
        get() = unresolvedSoundCounter.get()
    val startedSounds: Long
        get() = startedSoundCounter.get()
    val distanceRejectedSounds: Long
        get() = distanceRejectedSoundCounter.get()
    val missingBufferSounds: Long
        get() = missingBufferSoundCounter.get()

    @Volatile
    var lastRequestedSound: ResourceLocation? = null
        private set

    @Volatile
    var lastResolvedSound: ResourceLocation? = null
        private set

    @Volatile
    var lastUnresolvedSound: ResourceLocation? = null
        private set

    @Volatile
    var lastStartedSound: ResourceLocation? = null
        private set

    @Volatile
    var lastStartedPosition: Vec3d? = null
        private set

    @Volatile
    var listenerWorldPosition: Vec3f = Vec3f.EMPTY
        private set

    @Volatile
    private var enabled = profile.enabled


    fun init(latch: AbstractLatch) {
        Log.log(LogMessageType.AUDIO, LogLevels.INFO) { "Loading OpenAL..." }

        soundManager.load()
        Log.log(LogMessageType.AUDIO, LogLevels.VERBOSE) { "Preloading sounds..." }
        soundManager.preload()



        Log.log(LogMessageType.AUDIO, LogLevels.VERBOSE) { "Initializing OpenAL..." }
        device = alcOpenDevice(null as ByteBuffer?)
        check(device != MemoryUtil.NULL) { "Failed to open the default device." }

        try {
            context = alcCreateContext(device, null as IntBuffer?)
            check(context != MemoryUtil.NULL) { "Failed to create an OpenAL context." }
            check(alcSetThreadContext(context)) { "Failed to bind the OpenAL context." }

            val deviceCaps = ALC.createCapabilities(device)
            AL.createCapabilities(deviceCaps)
        } catch (error: Throwable) {
            releaseOpenAl()
            throw error
        }

        listener = SoundListener()
        syncListenerToCamera()

        val volumeConfig = session.profiles.audio.volume

        applyMasterVolume(volumeConfig.master)
        volumeConfig::master.observe(this) { volume -> queue += { applyMasterVolume(volume) } }

        session.events.listen<CameraPositionChangeEvent> {
            queue += {
                listener.position = Vec3f(it.position)
                listenerWorldPosition = listener.position
                listener.setOrientation(it.context.camera.view.view.front, CAMERA_UP_VEC3)
            }
        }

        DefaultAudioBehavior.register(session)

        Log.log(LogMessageType.AUDIO, LogLevels.INFO) { "OpenAL loaded!" }

        profile::enabled.observe(this, false) {
            if (it) {
                enabled = true
                return@observe
            }
            queue += {
                for (source in sources) {
                    source.stop()
                }
                enabled = false
            }
        }
        initialized = true
        session.world.audio = this
        latch.dec()
    }

    override fun play(sound: ResourceLocation, position: Vec3d?, volume: Float, pitch: Float) {
        if (!initialized) {
            return
        }
        if (!volume.isFinite() || volume < 0.0f || !pitch.isFinite() || pitch <= 0.0f) {
            return
        }
        if (position != null && (!position.x.isFinite() || !position.y.isFinite() || !position.z.isFinite())) {
            return
        }
        requestedSoundCounter.incrementAndGet()
        lastRequestedSound = sound
        FabricSoundEvents.dispatch(FabricSoundEventContext(session, sound, position, volume, pitch))
        queue += add@{
            val resolved = soundManager[sound]
            if (resolved == null) {
                unresolvedSoundCounter.incrementAndGet()
                lastUnresolvedSound = sound
                return@add
            }
            resolvedSoundCounter.incrementAndGet()
            lastResolvedSound = sound
            playSound(resolved, position, volume, pitch)
        }
    }

    private fun applyMasterVolume(volume: Float) {
        listener.masterVolume = if (volume.isFinite()) volume.coerceIn(0.0f, 1.0f) else 0.0f
        appliedMasterVolume = listener.masterVolume
    }

    override fun play2D(sound: ResourceLocation, volume: Float, pitch: Float) {
        if (!session.profiles.audio.gui.enabled) {
            return
        }
        super.play2D(sound, volume, pitch)
    }

    override fun stop(sound: ResourceLocation) {
        if (!profile.enabled) {
            return
        }
        queue += {
            for (source in sources) {
                if (!source.isPlaying) {
                    continue
                }
                if (source.sound?.soundEvent != sound) {
                    continue
                }
                source.stop()
            }
        }
    }

    override fun stopAll() {
        queue += {
            for (source in sources) {
                if (!source.isPlaying) {
                    continue
                }
                source.stop()
            }
        }
    }

    @Synchronized
    private fun getAvailableSource(): SoundSource? {
        for (source in sources.toSynchronizedList()) {
            if (source.available) {
                return source
            }
        }
        // no source available
        if (sources.size >= SoundConstants.MAX_SOURCES_AMOUNT) {
            return null
        }
        val source = SoundSource()
        sources += source

        return source
    }

    private fun shouldPlay(sound: Sound, position: Vec3d?): Boolean {
        if (position == null) return true
        val distance = Vec3dUtil.distance2(position, this.listener.position)
        val attenuation = sound.attenuationDistance.coerceAtLeast(0).toDouble()
        if (distance >= attenuation * attenuation) {
            return false
        }

        return true
    }

    private fun syncListenerToCamera() {
        val view = rendering.context.camera.view.view
        val position = Vec3f(view.eyePosition)
        listener.position = position
        listenerWorldPosition = position
        listener.setOrientation(view.front, CAMERA_UP_VEC3)
    }

    private fun playSound(sound: Sound, position: Vec3d? = null, volume: Float = 1.0f, pitch: Float = 1.0f) {
        if (!profile.enabled || profile.volume.master <= 0.0f) {
            return
        }
        queue += add@{
            // Rendering and audio initialize concurrently, so the first camera
            // event can precede listener registration. Refresh from the current
            // camera before evaluating any positional sound.
            syncListenerToCamera()
            position?.let {
                if (!shouldPlay(sound, position)) {
                    distanceRejectedSoundCounter.incrementAndGet()
                    return@add
                }
            }
            sound.load(session.assets)
            if (sound.buffer == null) {
                missingBufferSoundCounter.incrementAndGet()
                return@add
            }
            val effectivePitch = pitch * sound.pitch
            val effectiveGain = volume * sound.volume
            if (!effectivePitch.isFinite() || effectivePitch <= 0.0f || !effectiveGain.isFinite() || effectiveGain < 0.0f) {
                return@add
            }
            val source = getAvailableSource()
            if (source == null) {
                Log.log(LogMessageType.AUDIO, LogLevels.WARN) { "No source available: $sound" }
                return@add
            }
            position?.let {
                source.relative = false
                source.position = Vec3f(it)
            } ?: let {
                source.position = Vec3f.EMPTY
                source.relative = true
            }
            source.sound = sound
            source.pitch = effectivePitch
            source.gain = effectiveGain
            source.play()
            startedSoundCounter.incrementAndGet()
            lastStartedSound = sound.soundEvent
            lastStartedPosition = position
        }
    }

    private fun calculateAvailableSources() {
        var availableSources = 0
        for (source in sources) {
            if (source.available) {
                availableSources++
            }
        }
        this.availableSources = availableSources
    }

    fun startLoop() {
        while (true) {
            if (session.established || session.error != null) {
                break
            }
            queue.workBlocking(500.milliseconds)
            calculateAvailableSources()
            while (!enabled) {
                sleep(1.milliseconds)
                if (session.established || session.error != null) {
                    break
                }
            }
        }
    }

    fun exit() {
        if (!initialized && (device == MemoryUtil.NULL || device == -1L)) return
        initialized = false
        if (session.world.audio === this) session.world.audio = null
        Log.log(LogMessageType.AUDIO, LogLevels.INFO) { "Unloading OpenAL..." }

        Log.log(LogMessageType.AUDIO, LogLevels.VERBOSE) { "Unloading sounds..." }
        soundManager.unload()

        Log.log(LogMessageType.AUDIO, LogLevels.VERBOSE) { "Unloading sources..." }
        for (source in sources.toSynchronizedList()) {
            source.unload()
        }

        Log.log(LogMessageType.AUDIO, LogLevels.VERBOSE) { "Destroying OpenAL context..." }

        releaseOpenAl()

        Log.log(LogMessageType.AUDIO, LogLevels.INFO) { "Unloaded OpenAL!" }
    }

    private fun releaseOpenAl() {
        alcSetThreadContext(MemoryUtil.NULL)
        if (context != MemoryUtil.NULL && context != -1L) {
            alcDestroyContext(context)
            context = MemoryUtil.NULL
        }
        if (device != MemoryUtil.NULL && device != -1L) {
            alcCloseDevice(device)
            device = MemoryUtil.NULL
        }
    }
}
