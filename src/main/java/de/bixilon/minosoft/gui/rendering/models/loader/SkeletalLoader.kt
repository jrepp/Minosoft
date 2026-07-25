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
import de.bixilon.minosoft.assets.model.generation.PreparedContent
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentFormat
import de.bixilon.minosoft.assets.model.skeletal.SkeletalContentIdentity
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibBlockEntityModelRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibEntityModelRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibModelRouteRegistry
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibModelTarget
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerDefinition
import de.bixilon.minosoft.assets.model.skeletal.gecko.runtime.GeckoLibRenderLayerRegistry
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureContextFactory
import de.bixilon.minosoft.assets.model.texture.entity.EntityTextureMaterialFrame
import de.bixilon.minosoft.data.entities.entities.Entity
import de.bixilon.minosoft.data.registries.identified.ResourceLocationUtil.extend
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedSkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedEntityTextureLayer
import de.bixilon.minosoft.gui.rendering.skeletal.baked.BakedGeckoLibRenderLayer
import de.bixilon.minosoft.gui.rendering.util.mesh.MeshStates
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.SkeletalMesh
import de.bixilon.minosoft.gui.rendering.skeletal.mesh.SkeletalMeshBuilder
import de.bixilon.minosoft.gui.rendering.skeletal.binding.SkeletalModelBinder
import de.bixilon.minosoft.gui.rendering.skeletal.binding.SkeletalModelComposer
import de.bixilon.minosoft.gui.rendering.skeletal.model.SkeletalModel
import de.bixilon.minosoft.gui.rendering.skeletal.model.textures.SkeletalTextureMap
import de.bixilon.minosoft.gui.rendering.system.base.texture.texture.Texture
import de.bixilon.minosoft.gui.rendering.system.base.texture.array.StaticTextureArrayUpdate
import de.bixilon.minosoft.gui.rendering.textures.TextureUtil.texture
import de.bixilon.minosoft.gui.rendering.chunk.ChunkRenderer
import de.bixilon.minosoft.gui.rendering.entities.EntitiesRenderer
import de.bixilon.minosoft.util.logging.Log
import de.bixilon.minosoft.util.logging.LogLevels
import de.bixilon.minosoft.util.logging.LogMessageType

class SkeletalLoader(private val loader: ModelLoader) {
    private val registered: SynchronizedMap<ResourceLocation, RegisteredModel> = synchronizedMapOf()
    private val baked: MutableMap<ResourceLocation, BakedSkeletalModel> = HashMap()
    private val contentByEntity: MutableMap<ResourceLocation, ResourceLocation> = linkedMapOf()
    private val contentByIdentity: MutableMap<SkeletalContentIdentity, ResourceLocation> = linkedMapOf()
    private val contentNames: MutableSet<ResourceLocation> = linkedSetOf()
    private val reloadableEntityModels: MutableMap<ResourceLocation, RegisteredModel> = linkedMapOf()
    private val initialGenerationTextures: MutableSet<Texture> = linkedSetOf()
    private val initialReclaimableTextures: MutableSet<Texture> = linkedSetOf()
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
            if (registered.contentFidelity) {
                loadedTemplate.bindLoadedTextures(registered.override.keys, ::initialContentTexture)
            } else {
                loadedTemplate.load(loader.context, registered.override.keys)
            }
            registered.model = loadedTemplate
            discoverEntityTextureVariants(registered, loadedTemplate)
        }
        loaded = true
    }

    fun bake(latch: AbstractLatch?) {
        for ((name, registered) in this.registered) {
            val model = registered.model ?: continue
            val baked = bakeRegisteredModel(registered, model)
            val generationLease = if (registered.contentFidelity || registered.entityTextureLayers.isNotEmpty()) {
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
                    contentLease = generationLease,
                )
                if (registered.contentFidelity) contentNames += name
                if (!registered.contentFidelity && name.path.startsWith("models/entities/")) {
                    reloadableEntityModels[name] = registered
                }
            } catch (throwable: Throwable) {
                generationLease?.close()
                throw throwable
            }
        }
        contentLease?.let { lease ->
            if (initialGenerationTextures.isNotEmpty()) {
                val textureLease = loader.context.textures.static.retainGeneration(
                    initialGenerationTextures,
                    initialReclaimableTextures,
                )
                loader.context.session.contentFidelity.attachCleanup(lease.generationId, textureLease)
            }
        }
        initialGenerationTextures.clear()
        initialReclaimableTextures.clear()
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
                        ?.let { mapOf(binding.defaultMaterial to initialContentTexture(it)) }
                        ?: emptyMap()
                    register(name, model, override, contentFidelity = true)
                    discoverGeckoRenderLayers(registered.getValue(name), model) {
                        initialContentTexture(it)
                    }
                    model.contentIdentity?.let { identity ->
                        require(contentByIdentity.putIfAbsent(identity, name) == null) {
                            "Duplicate content-fidelity identity $identity."
                        }
                    }
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

    fun contentModel(entity: ResourceLocation): ResourceLocation? {
        val gecko = GeckoLibEntityModelRegistry.identity(entity)?.let(contentByIdentity::get)
        return gecko ?: contentByEntity[entity]
    }

    fun contentModel(target: GeckoLibModelTarget, identifier: ResourceLocation): ResourceLocation? =
        GeckoLibModelRouteRegistry.identity(target, identifier)?.let(contentByIdentity::get)

    /**
     * Re-parses, CPU-bakes, uploads, and atomically publishes content-fidelity
     * skeletal models and their static texture-array changes on the render
     * thread.
     */
    fun reloadContentFidelity(): Long {
        check(loaded) { "Skeletal models are not loaded." }
        check(Thread.currentThread() === loader.context.thread) {
            "Content-fidelity renderer reload must run on the render thread."
        }
        val session = loader.context.session
        val prepared = ContentFidelityLoader(session.assets, session.dataPacks).prepare()
        val textureGeneration = DeferredCloseable()
        val textureUpdate = loader.context.textures.static.beginUpdate()
        val candidate = try {
            prepareContentReload(prepared.value, textureUpdate)
        } catch (error: Throwable) {
            textureUpdate.closeSuppressing(error)
            prepared.cleanup.closeSuppressing(error)
            throw error
        }
        try {
            textureUpdate.upload()
            candidate.upload()
        } catch (error: Throwable) {
            candidate.retireSuppressing(error)
            textureUpdate.closeSuppressing(error)
            prepared.cleanup.closeSuppressing(error)
            throw error
        }

        var handedToStore = false
        return try {
            session.contentFidelity.reloadLeased(
                prepare = {
                    handedToStore = true
                    PreparedContent(
                        prepared.value,
                        AutoCloseable {
                            var failure: Throwable? = null
                            try {
                                textureGeneration.close()
                            } catch (error: Throwable) {
                                failure = error
                            }
                            try {
                                prepared.cleanup.close()
                            } catch (error: Throwable) {
                                failure?.addSuppressed(error) ?: run { failure = error }
                            }
                            failure?.let { throw it }
                        },
                    )
                },
                commit = { _, acquireLease ->
                    textureGeneration.set(applyContentReload(candidate, textureUpdate, acquireLease))
                },
            )
        } catch (error: Throwable) {
            candidate.retireSuppressing(error)
            textureUpdate.closeSuppressing(error)
            if (!handedToStore) prepared.cleanup.closeSuppressing(error)
            throw error
        }
    }

    fun entityTexture(entity: Entity, model: BakedSkeletalModel, tick: Long = entity.age.toLong()): EntityTextureMaterialFrame? {
        return entityTextures(entity, model, tick).values.singleOrNull()
    }

    fun entityTextures(
        entity: Entity,
        model: BakedSkeletalModel,
        tick: Long = entity.age.toLong(),
    ): Map<ResourceLocation, EntityTextureMaterialFrame> {
        val bases = if (model.entityTextureLayers.isNotEmpty()) {
            model.entityTextureLayers.keys
        } else {
            setOfNotNull(model.entityTextureBase)
        }
        if (bases.isEmpty()) return emptyMap()
        val snapshot = model.contentLease?.value ?: return emptyMap()
        val context = EntityTextureContextFactory.create(
            entity,
            snapshot.entityTextureCatalog.contextKeys(bases),
        )
        val entityKey = EntityTextureContextFactory.key(entity)
        return bases.mapNotNull { base ->
            snapshot.entityTextureCatalog.select(
                base = base,
                entityKey = entityKey,
                context = context,
                tick = tick,
                cache = snapshot.entityTextureCache,
            )?.let { base to it }
        }.toMap()
    }

    private fun discoverEntityTextureVariants(registered: RegisteredModel, model: SkeletalModel) {
        val snapshot = contentLease?.value ?: return
        discoverEntityTextureVariants(registered, model, snapshot) {
            initialContentTexture(it)
        }
    }

    private fun discoverGeckoRenderLayers(
        registered: RegisteredModel,
        model: SkeletalModel,
        texture: (ResourceLocation) -> Texture,
    ) {
        val identity = model.contentIdentity ?: return
        val snapshot = GeckoLibRenderLayerRegistry.snapshot(identity) ?: return
        for (definition in snapshot.definitions) {
            registered.geckoRenderLayers[definition.name] = RegisteredGeckoLibRenderLayer(
                snapshot.registrationId,
                definition,
                texture(definition.texture),
            )
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
            val layer = registered.entityTextureLayers.getOrPut(base) { RegisteredEntityTextureLayer() }
            layer.materials += material
            for (variant in entry.textures) {
                layer.textures.putIfAbsent(variant, texture(variant))
            }
        }
        registered.entityTextureBase = registered.entityTextureLayers.keys.singleOrNull()
    }

    /**
     * Keeps independently selectable ETF materials in separate meshes. This
     * mirrors ETF's render-layer interception without requiring a global
     * texture override for the complete entity model.
     */
    private fun bakeRegisteredModel(registered: RegisteredModel, model: SkeletalModel): BakedSkeletalModel {
        val layerMaterials = registered.entityTextureLayers.values
            .flatMapTo(linkedSetOf()) { it.materials }
        val baked = model.bake(
            registered.override,
            registered.mesh.buildMesh(loader.context),
            excludedMaterials = layerMaterials,
        )
        val layers = linkedMapOf<ResourceLocation, BakedEntityTextureLayer>()
        val geckoLayers = linkedMapOf<String, BakedGeckoLibRenderLayer>()
        val created = mutableListOf<de.bixilon.minosoft.gui.rendering.util.mesh.Mesh>()
        try {
            for ((base, registration) in registered.entityTextureLayers) {
                val meshes = linkedMapOf<ResourceLocation, de.bixilon.minosoft.gui.rendering.util.mesh.Mesh>()
                for ((textureName, texture) in registration.textures) {
                    val override = registration.materials.associateWith { texture }
                    val mesh = model.bake(
                        registered.override + override,
                        registered.mesh.buildMesh(loader.context),
                        includedMaterials = registration.materials,
                    ).mesh
                    meshes[textureName] = mesh
                    created += mesh
                }
                layers[base] = BakedEntityTextureLayer(registration.materials.toSet(), meshes)
            }
            for ((name, registration) in registered.geckoRenderLayers) {
                val override = model.textures.keys.associateWith { registration.texture }
                val mesh = model.bake(
                    registered.override + override,
                    registered.mesh.buildMesh(loader.context),
                ).mesh
                geckoLayers[name] = BakedGeckoLibRenderLayer(
                    registrationId = registration.registrationId,
                    blend = registration.definition.blend,
                    fullBright = registration.definition.fullBright,
                    mesh = mesh,
                )
                created += mesh
            }
        } catch (error: Throwable) {
            baked.retireSuppressing(error)
            for (mesh in created) {
                if (mesh.state != MeshStates.PREPARING) continue
                try {
                    mesh.drop()
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
        return baked.copy(
            entityTextureBase = registered.entityTextureBase,
            entityTextureLayers = layers,
            geckoRenderLayers = geckoLayers,
        )
    }

    private fun prepareContentReload(
        snapshot: ContentFidelitySnapshot,
        textureUpdate: StaticTextureArrayUpdate,
    ): ContentReloadCandidate {
        val candidate = linkedMapOf<ResourceLocation, BakedSkeletalModel>()
        val entities = linkedMapOf<ResourceLocation, ResourceLocation>()
        val identities = linkedMapOf<SkeletalContentIdentity, ResourceLocation>()
        val candidateContentNames = linkedSetOf<ResourceLocation>()
        fun candidateTexture(resource: ResourceLocation): Texture {
            val existing = loader.context.textures.static[resource]
            if (resource !in loader.context.session.assets && existing != null) {
                return textureUpdate.retain(existing)
            }
            require(resource in loader.context.session.assets) {
                "Live content-fidelity reload requires texture $resource to exist in the active asset stack."
            }
            return textureUpdate.resolve(resource)
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
                        ?.let { mapOf(binding.defaultMaterial to candidateTexture(it)) }
                        ?: emptyMap()
                    val registered = RegisteredModel(
                        template = null,
                        override = override,
                        mesh = SkeletalMesh,
                        model = model,
                        contentFidelity = true,
                    )
                    model.bindLoadedTextures(override.keys, ::candidateTexture)
                    discoverEntityTextureVariants(registered, model, snapshot, ::candidateTexture)
                    discoverGeckoRenderLayers(registered, model, ::candidateTexture)

                    val name = ContentSkeletalModelNames.model(source, content.identifier)
                    require(name !in candidate) { "Duplicate content-fidelity model $name." }
                    val complete = bakeRegisteredModel(registered, model)
                    candidate[name] = complete
                    candidateContentNames += name
                    complete.contentIdentity?.let { identity ->
                        require(identities.putIfAbsent(identity, name) == null) {
                            "Duplicate content-fidelity identity $identity."
                        }
                    }
                    if (content.format == SkeletalContentFormat.OPTIFINE_CEM) {
                        entities[entity] = name
                    }
                }
            }
            for ((name, retained) in reloadableEntityModels) {
                val model = retained.model ?: continue
                val registered = RegisteredModel(
                    template = retained.template,
                    override = retained.override,
                    mesh = retained.mesh,
                    model = model,
                )
                discoverEntityTextureVariants(registered, model, snapshot, ::candidateTexture)
                require(name !in candidate) { "Native and content-fidelity models collide at $name." }
                candidate[name] = bakeRegisteredModel(registered, model)
            }
            return ContentReloadCandidate(candidate, entities, identities, candidateContentNames)
        } catch (error: Throwable) {
            candidate.values.forEach { it.retireSuppressing(error) }
            throw error
        }
    }

    private fun applyContentReload(
        candidate: ContentReloadCandidate,
        textureUpdate: StaticTextureArrayUpdate,
        acquireLease: () -> ContentGenerationLease<ContentFidelitySnapshot>,
    ): AutoCloseable {
        try {
            candidate.models.values.forEach { model ->
                check(model.contentLease == null) { "Reload candidate already owns a content lease." }
                model.contentLease = acquireLease()
            }
        } catch (error: Throwable) {
            candidate.retireSuppressing(error)
            throw error
        }

        val previousNames = (contentNames + reloadableEntityModels.keys).toSet()
        val previousModels = previousNames.mapNotNull { name -> baked[name]?.let { name to it } }.toMap()
        val previousEntities = contentByEntity.toMap()
        val previousIdentities = contentByIdentity.toMap()
        try {
            textureUpdate.publish()
            previousNames.forEach(baked::remove)
            baked.putAll(candidate.models)
            contentNames.clear()
            contentNames.addAll(candidate.contentNames)
            contentByEntity.clear()
            contentByEntity.putAll(candidate.entities)
            contentByIdentity.clear()
            contentByIdentity.putAll(candidate.identities)
            loader.context.renderer[EntitiesRenderer]?.renderers?.reloadContentModels()
            if (GeckoLibBlockEntityModelRegistry.owners().isNotEmpty()) {
                loader.context.renderer[ChunkRenderer]?.let { chunks ->
                    chunks.unload(chunks.world)
                    chunks.invalidate(chunks.world)
                }
            }
            val textureGeneration = textureUpdate.complete()
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
            return textureGeneration
        } catch (error: Throwable) {
            candidate.models.keys.forEach(baked::remove)
            baked.putAll(previousModels)
            contentNames.clear()
            contentNames.addAll(previousNames)
            contentByEntity.clear()
            contentByEntity.putAll(previousEntities)
            contentByIdentity.clear()
            contentByIdentity.putAll(previousIdentities)
            textureUpdate.closeSuppressing(error)
            candidate.retireSuppressing(error)
            throw error
        }
    }

    private fun initialContentTexture(resource: ResourceLocation): Texture {
        val existing = loader.context.textures.static[resource]
        val texture = loader.context.textures.static.create(resource)
        initialGenerationTextures += texture
        if (existing == null) initialReclaimableTextures += texture
        return texture
    }

    private fun findEntityTexture(entity: ResourceLocation): ResourceLocation? {
        val candidates = listOf(
            ResourceLocation(entity.namespace, "textures/entity/${entity.path}/${entity.path}.png"),
            ResourceLocation(entity.namespace, "textures/entity/${entity.path}.png"),
        )
        return candidates.firstOrNull { it in loader.context.session.assets }
            ?: loader.context.session.assets.list("textures/entity/${entity.path}/")
                .asSequence()
                .filter { it.namespace == entity.namespace && it.path.endsWith(".png", ignoreCase = true) }
                .sortedWith(compareBy(ResourceLocation::path))
                .firstOrNull()
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
        val entityTextureLayers: MutableMap<ResourceLocation, RegisteredEntityTextureLayer> = linkedMapOf(),
        val geckoRenderLayers: MutableMap<String, RegisteredGeckoLibRenderLayer> = linkedMapOf(),
    )

    private data class RegisteredEntityTextureLayer(
        val materials: MutableSet<ResourceLocation> = linkedSetOf(),
        val textures: MutableMap<ResourceLocation, Texture> = linkedMapOf(),
    )

    private data class RegisteredGeckoLibRenderLayer(
        val registrationId: Long,
        val definition: GeckoLibRenderLayerDefinition,
        val texture: Texture,
    )

    private inner class ContentReloadCandidate(
        val models: Map<ResourceLocation, BakedSkeletalModel>,
        val entities: Map<ResourceLocation, ResourceLocation>,
        val identities: Map<SkeletalContentIdentity, ResourceLocation>,
        val contentNames: Set<ResourceLocation>,
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

    private class DeferredCloseable : AutoCloseable {
        private var value: AutoCloseable? = null
        private var closed = false

        @Synchronized
        fun set(value: AutoCloseable) {
            check(this.value == null) { "Deferred cleanup is already assigned." }
            if (closed) {
                value.close()
            } else {
                this.value = value
            }
        }

        @Synchronized
        override fun close() {
            if (closed) return
            closed = true
            value?.close()
            value = null
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
        val safeSource = source.path.lowercase().replace(Regex("[^a-z0-9/._-]"), "_")
        val safeIdentifier = identifier.lowercase().replace(Regex("[^a-z0-9/._-]"), "_")
        return ResourceLocation("minosoft", "content/${source.namespace}/$safeSource/$safeIdentifier.smodel")
    }

    fun entity(content: de.bixilon.minosoft.assets.model.skeletal.SkeletalContent): ResourceLocation {
        val file = content.source.path.substringAfterLast('/')
        val path = when {
            file.endsWith(".geo.json", ignoreCase = true) -> file.dropLast(".geo.json".length)
            else -> file.substringBeforeLast('.')
        }
        return ResourceLocation(content.source.namespace, path)
    }
}
