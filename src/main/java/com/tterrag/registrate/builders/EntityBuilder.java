package com.tterrag.registrate.builders;

import java.util.function.Supplier;

import com.tterrag.registrate.AbstractRegistrate;
import com.tterrag.registrate.fabric.EnvExecutor;
import com.tterrag.registrate.fabric.RegistryObject;
import com.tterrag.registrate.mixin.accessor.SpawnPlacementsAccessor;
import com.tterrag.registrate.providers.ProviderType;
import com.tterrag.registrate.providers.RegistrateLangProvider;
import com.tterrag.registrate.providers.loot.RegistrateEntityLootTables;
import com.tterrag.registrate.providers.loot.RegistrateLootTableProvider.LootType;
import com.tterrag.registrate.util.entry.EntityEntry;
import com.tterrag.registrate.util.entry.RegistryEntry;
import com.tterrag.registrate.util.nullness.NonNullBiConsumer;
import com.tterrag.registrate.util.nullness.NonNullConsumer;
import com.tterrag.registrate.util.nullness.NonNullFunction;
import com.tterrag.registrate.util.nullness.NonNullSupplier;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.SpawnPlacements.SpawnPredicate;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import org.jetbrains.annotations.Nullable;

/**
 * A builder for entities, allows for customization of the {@link EntityType.Builder}, easy creation of spawn egg items, and configuration of data associated with entities (loot tables, etc.).
 *
 * @param <T>
 *            The type of entity being built
 * @param <P>
 *            Parent object type
 */
public class EntityBuilder<T extends Entity, P> extends AbstractBuilder<EntityType<?>, EntityType<T>, P, EntityBuilder<T, P>> {

    /**
     * Create a new {@link EntityBuilder} and configure data. Used in lieu of adding side-effects to constructor, so that alternate initialization strategies can be done in subclasses.
     * <p>
     * The entity will be assigned the following data:
     * <ul>
     * <li>The default translation (via {@link #defaultLang()})</li>
     * </ul>
     *
     * @param <T>
     *            The type of the builder
     * @param <P>
     *            Parent object type
     * @param owner
     *            The owning {@link AbstractRegistrate} object
     * @param parent
     *            The parent object
     * @param name
     *            Name of the entry being built
     * @param callback
     *            A callback used to actually register the built entry
     * @param factory
     *            Factory to create the entity
     * @param classification
     *            The {@link MobCategory} of the entity
     * @return A new {@link EntityBuilder} with reasonable default data generators.
     */
    public static <T extends Entity, P> EntityBuilder<T, P> create(AbstractRegistrate<?> owner, P parent, String name, BuilderCallback callback, EntityType.EntityFactory<T> factory,
            MobCategory classification) {
        return new EntityBuilder<>(owner, parent, name, callback, factory, classification)
                .defaultLang();
    }

    private final NonNullSupplier<FabricEntityTypeBuilder<T>> builder;

    private NonNullConsumer<FabricEntityTypeBuilder<T>> builderCallback = $ -> {};

    @Nullable
    private NonNullSupplier<NonNullFunction<EntityRendererProvider.Context, EntityRenderer<? super T>>> renderer;

    private boolean attributesConfigured, spawnConfigured; // TODO make this more reuse friendly


    protected EntityBuilder(AbstractRegistrate<?> owner, P parent, String name, BuilderCallback callback, EntityType.EntityFactory<T> factory, MobCategory classification) {
        super(owner, parent, name, callback, Registry.ENTITY_TYPE_REGISTRY);
        this.builder = () -> FabricEntityTypeBuilder.create(classification, factory);
    }

    /**
     * Modify the properties of the entity. Modifications are done lazily, but the passed function is composed with the current one, and as such this method can be called multiple times to perform
     * different operations.
     *
     * @param cons
     *            The action to perform on the properties
     * @return this {@link EntityBuilder}
     */
    public EntityBuilder<T, P> properties(NonNullConsumer<FabricEntityTypeBuilder<T>> cons) {
        builderCallback = builderCallback.andThen(cons);
        return this;
    }

    /**
     * Register an {@link EntityRenderer} for this entity.
     * <p>
     *
     * @param renderer
     *            A (server safe) supplier to an {@link EntityRendererProvider} that will provide this entity's renderer
     * @return this {@link EntityBuilder}
     */
    public EntityBuilder<T, P> renderer(NonNullSupplier<NonNullFunction<EntityRendererProvider.Context, EntityRenderer<? super T>>> renderer) {
        if (this.renderer == null) { // First call only
            EnvExecutor.runWhenOn(EnvType.CLIENT, () -> this::registerRenderer);
        }
        this.renderer = renderer;
        return this;
    }

    protected void registerRenderer() {
        onRegister(entry -> {
            try {
                EntityRendererRegistry.register(entry, renderer.get()::apply);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to register renderer for Entity " + get().getId(), e);
            }
        });
    }

    /**
     * Register a attributes for this entity. The entity must extend {@link LivingEntity}.
     * <p>
     * Cannot be called more than once per builder.
     *
     * @param attributes
     *            A supplier to the attributes for this entity, usually of the form {@code EntityClass::createAttributes}
     * @return this {@link EntityBuilder}
     * @throws IllegalStateException
     *             When called more than once
     */
    @SuppressWarnings("unchecked")
    public EntityBuilder<T, P> attributes(Supplier<AttributeSupplier.Builder> attributes) {
        if (attributesConfigured) {
            throw new IllegalStateException("Cannot configure attributes more than once");
        }
        attributesConfigured = true;
        this.onRegister(entry -> FabricDefaultAttributeRegistry.register((EntityType<? extends LivingEntity>) entry, attributes.get()));
        return this;
    }

    /**
     * Register a spawn placement for this entity. The entity must extend {@link Mob} and allow construction with a {@code null} {@link Level}.
     * <p>
     * Cannot be called more than once per builder.
     *
     * @param type
     *            The type of placement to use
     * @param heightmap
     *            Which heightmap to use to choose placement locations
     * @param predicate
     *            A predicate to check spawn locations for validity
     * @return this {@link EntityBuilder}
     * @throws IllegalStateException
     *             When called more than once
     */
    @SuppressWarnings("unchecked")
    public EntityBuilder<T, P> spawnPlacement(SpawnPlacements.Type type, Heightmap.Types heightmap, SpawnPredicate<T> predicate) {
        if (spawnConfigured) {
            throw new IllegalStateException("Cannot configure spawn placement more than once");
        }
        spawnConfigured = true;
        this.onRegister(t -> {
            /* TODO is there any way to do this now?
            try {
                if (!(t.create(null) instanceof MobEntity)) {
                    throw new IllegalArgumentException("Cannot register spawn placement for entity " + t.getRegistryName() + " as it does not extend MobEntity");
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to type check entity " + t.getRegistryName() + " when registering spawn placement", e);
            }
            */
            SpawnPlacementsAccessor.callRegister((EntityType<Mob>) t, type, heightmap, (SpawnPredicate<Mob>) predicate);
        });
        return this;
    }

    /**
     * Create a spawn egg item for this entity using the given colors, not allowing for any extra configuration.
     *
     * @deprecated This does not work properly, see <a href="https://github.com/MinecraftForge/MinecraftForge/pull/6299">this issue</a>.
     *             <p>
     *             As a temporary measure, uses a custom egg class that imperfectly emulates the functionality
     *
     * @param primaryColor
     *            The primary color of the egg
     * @param secondaryColor
     *            The secondary color of the egg
     * @return this {@link EntityBuilder}
     */
    @Deprecated
    public EntityBuilder<T, P> defaultSpawnEgg(int primaryColor, int secondaryColor) {
        return spawnEgg(primaryColor, secondaryColor).build();
    }

    /**
     * Create a spawn egg item for this entity using the given colors, and return the builder for further configuration.
     *
     * @deprecated This does not work properly, see <a href="https://github.com/MinecraftForge/MinecraftForge/pull/6299">this issue</a>.
     *             <p>
     *             As a temporary measure, uses a custom egg class that imperfectly emulates the functionality
     *
     * @param primaryColor
     *            The primary color of the egg
     * @param secondaryColor
     *            The secondary color of the egg
     * @return the {@link ItemBuilder} for the egg item
     */
    @SuppressWarnings({"unchecked" })
    @Deprecated
    public ItemBuilder<? extends SpawnEggItem, EntityBuilder<T, P>> spawnEgg(int primaryColor, int secondaryColor) {
        return getOwner().item(this, getName() + "_spawn_egg", p -> new SpawnEggItem((EntityType<? extends Mob>) get().get(), primaryColor, secondaryColor, p)).properties(p -> p.tab(CreativeModeTab.TAB_MISC))
                .model((ctx, prov) -> prov.withExistingParent(ctx.getName(), new ResourceLocation("item/template_spawn_egg")));
    }

    /**
     * Assign the default translation, as specified by {@link RegistrateLangProvider#getAutomaticName(NonNullSupplier, net.minecraft.resources.ResourceKey)}. This is the default, so it is generally
     * not necessary to call, unless for undoing previous changes.
     *
     * @return this {@link EntityBuilder}
     */
    public EntityBuilder<T, P> defaultLang() {
        return lang(EntityType::getDescriptionId);
    }

    /**
     * Set the translation for this entity.
     *
     * @param name
     *            A localized English name
     * @return this {@link EntityBuilder}
     */
    public EntityBuilder<T, P> lang(String name) {
        return lang(EntityType::getDescriptionId, name);
    }

    /**
     * Configure the loot table for this entity. This is different than most data gen callbacks as the callback does not accept a {@link DataGenContext}, but instead a
     * {@link RegistrateEntityLootTables}, for creating specifically entity loot tables.
     *
     * @param cons
     *            The callback which will be invoked during entity loot table creation.
     * @return this {@link EntityBuilder}
     */
    public EntityBuilder<T, P> loot(NonNullBiConsumer<RegistrateEntityLootTables, EntityType<T>> cons) {
        return setData(ProviderType.LOOT, (ctx, prov) -> prov.addLootAction(LootType.ENTITY, tb -> cons.accept((RegistrateEntityLootTables) tb, ctx.getEntry())));
    }

    /**
     * Assign {@link TagKey}{@code s} to this entity. Multiple calls will add additional tags.
     *
     * @param tags
     *            The tags to assign
     * @return this {@link EntityBuilder}
     */
    @SafeVarargs
    public final EntityBuilder<T, P> tag(TagKey<EntityType<?>>... tags) {
        return tag(ProviderType.ENTITY_TAGS, tags);
    }

    @Override
    protected EntityType<T> createEntry() {
        FabricEntityTypeBuilder<T> builder = this.builder.get();
        builderCallback.accept(builder);
        return builder.build(/*getName()*/);
    }
@Deprecated
    protected void injectSpawnEggType(EntityType<T> entry) {}

    @Override
    protected RegistryEntry<EntityType<T>> createEntryWrapper(RegistryObject<EntityType<T>> delegate) {
        return new EntityEntry<>(getOwner(), delegate);
    }

    @Override
    public EntityEntry<T> register() {
        return (EntityEntry<T>) super.register();
    }
}
