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

package de.bixilon.minosoft.gui.rendering.models.loader

import de.bixilon.kutil.collections.CollectionUtil.synchronizedMapOf
import de.bixilon.kutil.collections.map.SynchronizedMap
import de.bixilon.kutil.latch.AbstractLatch
import de.bixilon.kutil.reflection.ReflectionUtil.forceSet
import de.bixilon.minosoft.assets.util.InputStreamUtil.readJson
import de.bixilon.minosoft.data.registries.identified.ResourceLocation
import de.bixilon.minosoft.assets.model.generation.ContentGenerationLease
import de.bixilon.minosoft.assets.model.generation.ContentFidelitySnapshot
import de.bixilon.minosoft.assets.model.generation.ContentFidelityLoader
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureContextFactory
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureMaterialFrame
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.registries.identified.ResourceLocationUtil.extend
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.SkeletalMesh
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.SkeletalMeshBuilder
import de.bixilon.minosoft.gui.rendering.skeletal.binding.SkeletalModelBinder
import de.bixilon.minosoft.gui.rendering.skeletal.binding.SkeletalModelComposer
import de.bixilon.minosoft.gui.rendering.skeletal.model.SkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.model.textures.SkeletalTextureMap
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType

class SkeletalLoader(private val loader: ModelLoader) {
    private val registered: SynchronizedMap<ResourceLocation, RegisteredModel> = synchronizedMapOf()
    private val baked: MutableMap<ResourceLocation, BakedSkeletalModel> = HashMap()
    private val contentByEntity: MutableMap<ResourceLocation, ResourceLocation> = linkedMapOf()
    private val contentNames: MutableSet<ResourceLocation> = linkedSetOf()
    private var contentLease: ContentGenerationLease<ContentFidelitySnapshot>? = null
    private var loaded = false

    fun load(latch: AbstractLatch?) {
        if (loaded) throw IllegalStateException("Already loaded!")
        val templates: MutableMap<ResourceLocation, SkeletalModel> = HashMap(this.registered.size, 0.1f)

        for ((_, registered) in this.registered) {
            var template = registered.model ?: registered.template?.let(templates::get)
            if (template == null && registered.template != null) {
                template = loader.context.session.assets.getOrNull(registered.template)?.readJson()
                if (template == null) {
                    Log.log(LogMessageType.LOADING, LogLevels.WARN) { "Can not find skeletal model ${registered.template}" }
                    continue
                }
                templates[registered.template] = template
            }

            val loadedTemplate = template ?: continue
            loadedTemplate.load(loader.context, registered.override.keys)
            registered.model = loadedTemplate
            discoverEntityTextureVariants(registered, loadedTemplate)
        }
        loaded = true
    }

    fun bake(latch: AbstractLatch?) {
        for ((name, registered) in this.registered) {
            val model = registered.model ?: continue
            val baked = model.bake(registered.override, registered.mesh.buildMesh(loader.context))
            val materialMeshes = registered.materialOverrides.mapValues { (_, override) ->
                model.bake(registered.override + override, registered.mesh.buildMesh(loader.context)).mesh
            }
            val generationLease = if (registered.contentFidelity) {
                loader.context.session.contentFidelity.lease()
                    ?: throw IllegalStateException("Content-fidelity generation disappeared while baking $name.")
            } else {
                null
            }
            try {
                contentLease?.let {
                    check(generationLease == null || generationLease.generationId == it.generationId) {
                        "Content-fidelity generation changed while baking $name."
                    }
                }
                this.baked[name] = baked.copy(
                    materialMeshes = materialMeshes,
                    entityTextureBase = registered.entityTextureBase,
                    contentLease = generationLease,
                )
                if (registered.contentFidelity) contentNames += name
            } catch (throwable: Throwable) {
                generationLease?.close()
                throw throwable
            }
        }
        contentLease?.close()
        contentLease = null
    }

    fun upload() {
        for ((_, baked) in this.baked) {
            baked.load()
        }
    }

    fun cleanup() {
        this::registered.forceSet(null)
    }

    operator fun get(name: ResourceLocation): BakedSkeletalModel? {
        return this.baked[name]
    }

    fun register(name: ResourceLocation, template: ResourceLocation = name, override: SkeletalTextureMap = emptyMap(), mesh: SkeletalMeshBuilder = SkeletalMesh) {
        if (loaded) throw IllegalStateException("Already loaded!")
        val previous = this.registered.putIfAbsent(name, RegisteredModel(template, override, mesh))
        if (previous != null) throw IllegalArgumentException("A model with the name $name was already registered!")
    }

    fun register(
        name: ResourceLocation,
        model: SkeletalModel,
        override: SkeletalTextureMap = emptyMap(),
        mesh: SkeletalMeshBuilder = SkeletalMesh,
        contentFidelity: Boolean = false,
    ) {
        if (loaded) throw IllegalStateException("Already loaded!")
        val previous = this.registered.putIfAbsent(name, RegisteredModel(null, override, mesh, model, contentFidelity = contentFidelity))
        if (previous != null) throw IllegalArgumentException("A model with the name $name was already registered!")
    }

    fun registerContentFidelity() {
        check(contentLease == null) { "Content-fidelity models are already registered." }
        val lease = loader.context.session.contentFidelity.lease() ?: return
        contentLease = lease
        try {
            for ((source, contents) in lease.value.skeletal) {
                for (content in contents) {
                    val entity = ContentSkeletalModelNames.entity(content)
                    val binding = SkeletalModelBinder.bind(
                        content,
                        versionId = loader.context.session.version.versionId,
                        entity = entity,
                    )
                    val model = if (content.format == SkeletalContentFormat.OPTIFINE_CEM) {
                        findNativeEntityModel(entity)?.let { SkeletalModelComposer.compose(it, binding) } ?: binding.model
                    } else {
                        binding.model
                    }
                    val name = ContentSkeletalModelNames.model(source, content.identifier)
                    val override = model.textures[binding.defaultMaterial]
                        ?.takeIf { it.source == null }
                        ?.let { findEntityTexture(ContentSkeletalModelNames.entity(content)) }
                        ?.let { mapOf(binding.defaultMaterial to loader.context.textures.static.create(it)) }
                        ?: emptyMap()
                    register(name, model, override, contentFidelity = true)
                    if (content.format == SkeletalContentFormat.OPTIFINE_CEM) {
                        contentByEntity[entity] = name
                    }
                }
            }
        } catch (throwable: Throwable) {
            contentLease = null
            try {
                lease.close()
            } catch (cleanup: Throwable) {
                throwable.addSuppressed(cleanup)
            }
            throw throwable
        }
    }

    fun contentModel(entity: ResourceLocation): ResourceLocation? = contentByEntity[entity]

    /**
     * Re-parses, CPU-bakes, uploads, and atomically publishes content-fidelity
     * skeletal models on the render thread. The existing uploaded texture array
     * is reused; introducing a new texture rejects the candidate and preserves
     * the active model generation.
     */
    fun reloadContentFidelity(): Long {
        check(loaded) { "Skeletal models are not loaded." }
        check(Thread.currentThread() === loader.context.thread) {
            "Content-fidelity renderer reload must run on the render thread."
        }
        val session = loader.context.session
        val prepared = ContentFidelityLoader(session.assets, session.dataPacks).prepare()
        val candidate = try {
            prepareContentReload(prepared.value)
        } catch (error: Throwable) {
            prepared.cleanup.closeSuppressing(error)
            throw error
        }
        try {
            candidate.upload()
        } catch (error: Throwable) {
            candidate.retireSuppressing(error)
            prepared.cleanup.closeSuppressing(error)
            throw error
        }

        var handedToStore = false
        return try {
            session.contentFidelity.reloadLeased(
                prepare = {
                    handedToStore = true
                    prepared
                },
                commit = { _, acquireLease ->
                    applyContentReload(candidate, acquireLease)
                },
            )
        } catch (error: Throwable) {
            candidate.retireSuppressing(error)
            if (!handedToStore) prepared.cleanup.closeSuppressing(error)
            throw error
        }
    }

    fun entityTexture(entity: Entity, model: BakedSkeletalModel, tick: Long = entity.age.toLong()): EntityTextureMaterialFrame? {
        val base = model.entityTextureBase ?: return null
        val snapshot = model.contentLease?.value ?: return null
        val context = EntityTextureContextFactory.create(entity)
        return snapshot.entityTextureCatalog.select(
            base = base,
            entityKey = EntityTextureContextFactory.key(entity),
            context = context,
            tick = tick,
            cache = snapshot.entityTextureCache,
        )
    }

    private fun discoverEntityTextureVariants(registered: RegisteredModel, model: SkeletalModel) {
        val snapshot = contentLease?.value ?: return
        discoverEntityTextureVariants(registered, model, snapshot) {
            loader.context.textures.static.create(it)
        }
    }

    private fun discoverEntityTextureVariants(
        registered: RegisteredModel,
        model: SkeletalModel,
        snapshot: ContentFidelitySnapshot,
        texture: (ResourceLocation) -> Texture,
    ) {
        for ((material, properties) in model.textures) {
            val base = properties.source ?: material.texture()
            val entry = snapshot.entityTextureCatalog[base] ?: continue
            if (registered.entityTextureBase != null && registered.entityTextureBase != base) {
                Log.log(LogMessageType.LOADING, LogLevels.WARN) {
                    "Skeletal model has multiple ETF base materials; only ${registered.entityTextureBase} is selectable per instance."
                }
                continue
            }
            registered.entityTextureBase = base
            for (variant in entry.textures) {
                registered.materialOverrides[variant] = mapOf(material to texture(variant))
            }
        }
    }

    private fun prepareContentReload(snapshot: ContentFidelitySnapshot): ContentReloadCandidate {
        val candidate = linkedMapOf<ResourceLocation, BakedSkeletalModel>()
        val entities = linkedMapOf<ResourceLocation, ResourceLocation>()
        fun existingTexture(resource: ResourceLocation): Texture = requireNotNull(loader.context.textures.static[resource]) {
            "Live content-fidelity reload requires texture $resource to exist in the uploaded static array."
        }

        try {
            for ((source, contents) in snapshot.skeletal) {
                for (content in contents) {
                    val entity = ContentSkeletalModelNames.entity(content)
                    val binding = SkeletalModelBinder.bind(
                        content,
                        versionId = loader.context.session.version.versionId,
                        entity = entity,
                    )
                    val model = if (content.format == SkeletalContentFormat.OPTIFINE_CEM) {
                        findNativeEntityModel(entity)?.let { SkeletalModelComposer.compose(it, binding) } ?: binding.model
                    } else {
                        binding.model
                    }
                    val override = model.textures[binding.defaultMaterial]
                        ?.takeIf { it.source == null }
                        ?.let { findEntityTexture(entity) }
                        ?.let { mapOf(binding.defaultMaterial to existingTexture(it)) }
                        ?: emptyMap()
                    val registered = RegisteredModel(
                        template = null,
                        override = override,
                        mesh = SkeletalMesh,
                        model = model,
                        contentFidelity = true,
                    )
                    model.bindLoadedTextures(loader.context, override.keys)
                    discoverEntityTextureVariants(registered, model, snapshot, ::existingTexture)

                    val name = ContentSkeletalModelNames.model(source, content.identifier)
                    require(name !in candidate) { "Duplicate content-fidelity model $name." }
                    val baked = model.bake(override, SkeletalMesh.buildMesh(loader.context))
                    val materials = linkedMapOf<ResourceLocation, de.bixilon.minosoft.gui.rendering.util.mesh.Mesh>()
                    try {
                        for ((material, materialOverride) in registered.materialOverrides) {
                            materials[material] = model.bake(
                                override + materialOverride,
                                SkeletalMesh.buildMesh(loader.context),
                            ).mesh
                        }
                    } catch (error: Throwable) {
                        baked.retireSuppressing(error)
                        for (mesh in materials.values) {
                            if (mesh.state != MeshStates.PREPARING) continue
                            try {
                                mesh.drop()
                            } catch (cleanup: Throwable) {
                                error.addSuppressed(cleanup)
                            }
                        }
                        throw error
                    }
                    val complete = baked.copy(
                        materialMeshes = materials,
                        entityTextureBase = registered.entityTextureBase,
                    )
                    candidate[name] = complete
                    if (content.format == SkeletalContentFormat.OPTIFINE_CEM) {
                        entities[entity] = name
                    }
                }
            }
            return ContentReloadCandidate(candidate, entities)
        } catch (error: Throwable) {
            candidate.values.forEach { it.retireSuppressing(error) }
            throw error
        }
    }

    private fun applyContentReload(
        candidate: ContentReloadCandidate,
        acquireLease: () -> ContentGenerationLease<ContentFidelitySnapshot>,
    ) {
        try {
            candidate.models.values.forEach { model ->
                check(model.contentLease == null) { "Reload candidate already owns a content lease." }
                model.contentLease = acquireLease()
            }
        } catch (error: Throwable) {
            candidate.retireSuppressing(error)
            throw error
        }

        val previousNames = contentNames.toSet()
        val previousModels = previousNames.mapNotNull { name -> baked[name]?.let { name to it } }.toMap()
        val previousEntities = contentByEntity.toMap()
        try {
            previousNames.forEach(baked::remove)
            baked.putAll(candidate.models)
            contentNames.clear()
            contentNames.addAll(candidate.models.keys)
            contentByEntity.clear()
            contentByEntity.putAll(candidate.entities)
            loader.context.renderer[EntitiesRenderer]?.renderers?.reloadContentModels()
        } catch (error: Throwable) {
            candidate.models.keys.forEach(baked::remove)
            baked.putAll(previousModels)
            contentNames.clear()
            contentNames.addAll(previousNames)
            contentByEntity.clear()
            contentByEntity.putAll(previousEntities)
            candidate.retireSuppressing(error)
            throw error
        }
        candidate.published = true
        for (previous in previousModels.values) {
            try {
                previous.retire()
            } catch (error: Throwable) {
                Log.log(LogMessageType.RENDERING, LogLevels.WARN) {
                    "Content-fidelity model retired with a cleanup failure: $error"
                }
            }
        }
    }

    private fun findEntityTexture(entity: ResourceLocation): ResourceLocation? {
        val candidates = listOf(
            ResourceLocation(entity.namespace, "textures/entity/${entity.path}/${entity.path}.png"),
            ResourceLocation(entity.namespace, "textures/entity/${entity.path}.png"),
        )
        return candidates.firstOrNull { it in loader.context.session.assets }
    }

    private fun findNativeEntityModel(entity: ResourceLocation): SkeletalModel? {
        val path = entity.path.substringAfterLast('/')
        val candidates = listOf(
            ResourceLocation(entity.namespace, "models/entities/${entity.path}/$path.smodel"),
            ResourceLocation(entity.namespace, "models/entities/${entity.path}.smodel"),
        )
        return candidates.firstNotNullOfOrNull { loader.context.session.assets.getOrNull(it)?.readJson() }
    }

    fun unload() {
        var failure: Throwable? = null
        for ((_, baked) in this.baked) {
            try {
                baked.retire()
            } catch (throwable: Throwable) {
                failure?.addSuppressed(throwable) ?: run { failure = throwable }
            }
        }
        try {
            contentLease?.close()
        } catch (throwable: Throwable) {
            failure?.addSuppressed(throwable) ?: run { failure = throwable }
        }
        contentLease = null
        failure?.let { throw it }
    }

    private data class RegisteredModel(
        val template: ResourceLocation?,
        val override: SkeletalTextureMap,
        var mesh: SkeletalMeshBuilder,
        var model: SkeletalModel? = null,
        val contentFidelity: Boolean = false,
        var entityTextureBase: ResourceLocation? = null,
        val materialOverrides: MutableMap<ResourceLocation, SkeletalTextureMap> = linkedMapOf(),
    )

    private class ContentReloadCandidate(
        val models: Map<ResourceLocation, BakedSkeletalModel>,
        val entities: Map<ResourceLocation, ResourceLocation>,
    ) {
        var published = false

        fun upload() {
            try {
                models.values.forEach(BakedSkeletalModel::load)
            } catch (error: Throwable) {
                retireSuppressing(error)
                throw error
            }
        }

        fun retireSuppressing(error: Throwable) {
            if (published) return
            for (model in models.values) {
                model.retireSuppressing(error)
            }
        }
    }

    private fun BakedSkeletalModel.retireSuppressing(error: Throwable) {
        try {
            retire()
        } catch (cleanup: Throwable) {
            error.addSuppressed(cleanup)
        }
    }

    private fun AutoCloseable.closeSuppressing(error: Throwable) {
        try {
            close()
        } catch (cleanup: Throwable) {
            error.addSuppressed(cleanup)
        }
    }

    companion object {

        fun ResourceLocation.sModel(): ResourceLocation {
            return this.extend(prefix = "models/", suffix = ".smodel")
        }
    }
}

internal object ContentSkeletalModelNames {
    fun model(source: ResourceLocation, identifier: String): ResourceLocation {
        val safeIdentifier = identifier.lowercase().replace(Regex("[^a-z0-9/._-]"), "_")
        return ResourceLocation("minosoft", "content/${source.namespace}/$safeIdentifier.smodel")
    }

    fun entity(content: de.bixilon.minosoft.assets.model.skeletal.SkeletalContent): ResourceLocation {
        val path = content.source.path.substringAfterLast('/').substringBeforeLast('.')
        return ResourceLocation(content.source.namespace, path)
    }
}
