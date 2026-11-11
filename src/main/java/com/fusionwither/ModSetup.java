/*
 * 【文件职责】模组加载期（MOD 总线）的事件订阅处：对原版凋灵做属性层面的改动，并把运行时事件挂上游戏总线。
 * 【所属模块】模组入口（com.fusionwither 包根），与 GameBusEvents 一同构成「按总线拆类」的两半。
 * 【协作关系】@Mod.EventBusSubscriber(bus = MOD) 让 Forge 自动注册本类的 @SubscribeEvent；
 *   NewMod 构造器调用 registerGameBusEvents()，由它把 WitherBossHandler 的运行时回调挂到游戏总线。
 * 【关键约束】本类声明的是 MOD 总线，绝不能放游戏总线事件（详见 GameBusEvents 类注释里的启动崩溃堆栈）；
 *   血量这类数值改动一律交给 GameBusEvents 的永久修饰符，本类不碰具体数值。
 */
package com.fusionwither;

import com.fusionwither.wither.WitherBossHandler;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 模组 MOD 总线的事件订阅处：所有「这个模组改了原版哪些东西」的注册期改动都集中在这里，方便排查冲突。
 *
 * <p><b>为什么必须是 {@code final} 且构造器私有：</b>本类只是事件的挂载点，没有任何需要实例化的状态；
 * 禁止实例化可以避免有人把它当成工具类使用，从而保证「属性改动只发生在 {@link #onModifyEntityAttributes} 一处」。
 *
 * <p><b>为什么不在这里改血量 / 护甲数值：</b>属性改动分两类 —— 「本模组永久性数值」由
 * {@link GameBusEvents#onEntityJoin} 的永久修饰符按实体逐个施加；本类只负责属性层面的
 * 「存在性」与「新增缺失属性」。若把数值也写在这里，就会出现同一语义两处实现、必然发生行为漂移。
 *
 * <p><b>与 {@link GameBusEvents} 的关系：</b>两者是同一份职责按总线类型切开的两半 ——
 * 本类只处理 MOD 总线（注册表已就绪、实体类型可查询的时机），运行期事件全部交给 {@link GameBusEvents}。
 */
@Mod.EventBusSubscriber(modid = NewMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModSetup {

    private ModSetup() {}

    /**
     * 在实体属性注册阶段对原版凋灵做属性层改动。
     *
     * <p><b>为什么用 {@link EntityAttributeModificationEvent} 而不是实体属性创建事件：</b>
     * 创建事件只在实体首次注册属性时生效，对原版已经注册过的 {@code EntityType.WITHER} 是空操作 ——
     * 那样凋灵的血量根本改不动。本事件才是为「修改已有实体的属性表」设计的。
     *
     * <p><b>调用时机：</b>Forge 在实体属性注册完成后、注册表冻结之前触发；此时属性实例尚未被任何实体持有，
     * 因此在这里增删属性是安全的。
     *
     * <p><b>副作用：</b>最多向凋灵属性表新增一个击退抗性项；不修改任何已有属性的数值。
     * 属性缺失时只打一条 warn 并提前返回，让模组继续加载而不是崩在启动期。
     *
     * @param event 实体属性修改事件，其中的 {@code add}/{@code has} 按实体类型与属性配对操作
     */
    @SubscribeEvent
    public static void onModifyEntityAttributes(EntityAttributeModificationEvent event) {
        if (!event.has(EntityType.WITHER, Attributes.MAX_HEALTH)) {
            NewMod.LOGGER.warn("[凋灵] 凋灵没有 MAX_HEALTH 属性，无法改血量");
            return;
        }
        // 只做存在性检查与记录；要调数值一律走 GameBusEvents 的永久修饰符。
        // 这里唯一的例外是 KNOCKBACK_RESISTANCE —— 原版凋灵**没有**这个属性，
        // 对它调用 add 是「新增」而不是「覆盖」旧值，因此不会破坏原版数值。
        //
        // 反面教训：不能用 add 去「确保属性存在」。Forge 的 add(type, attribute) 会转发成
        // add(type, attribute, attribute.getDefaultValue())，即用属性自身的通用默认值覆盖实体原有基值。
        // 上一版对已存在的属性也调了 add，实测把凋灵 MAX_HEALTH 从 300 覆盖成 20、
        // ARMOR 从 4.0 覆盖成 0.0（还凭空造出一个 0.0 的击退抗性），
        // 于是血量上限只能靠 GameBusEvents 里按当前上限反推的修饰符硬算回来 ——
        // 等于用一个自造的问题换一次校准，还让所有凋灵的基值都变错了。
        event.add(EntityType.WITHER, Attributes.KNOCKBACK_RESISTANCE);
        NewMod.LOGGER.info("[凋灵] 属性已注册（血量差值将在实体生成时用永久修饰符应用）");
    }

    /**
     * 把凋灵 Boss 的运行时事件挂到游戏总线上 —— 本模组战斗逻辑的唯一接线点。
     *
     * <p><b>调用时机：</b>{@link NewMod} 构造器尾部调用一次，此时 MOD 总线上的注册已完成，
     * 挂游戏总线可以确保实体类型与配置都已可用。
     *
     * <p><b>为什么用 {@code addListener} 而不是把 {@link WitherBossHandler} 也标成订阅类：</b>
     * 处理方法的归属（哪个类处理哪种事件）与「事件挂在哪条总线」是两回事；
     * 在这里显式列出全部入口，一眼就能看全凋灵在运行期接管了哪几件事 ——
     * 实体 tick 驱动（{@link WitherBossHandler#onLivingTick}）、致死伤害改写、
     * 弹射物拦截、爆炸判定改写、命令注册。
     *
     * <p><b>副作用：</b>向游戏总线注册 5 个监听器，不产生任何即时行为。
     */
    public static void registerGameBusEvents() {
        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener(WitherBossHandler::onLivingTick);
        bus.addListener(WitherBossHandler::onLivingDamage);
        bus.addListener(WitherBossHandler::onProjectileImpact);
        bus.addListener(WitherBossHandler::onExplosionDetonate);
        bus.addListener(WitherBossHandler::onRegisterCommands);
    }
}
