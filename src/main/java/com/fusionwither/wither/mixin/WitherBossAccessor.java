/*
 * 【文件职责】给凋零的私有 Boss 血条字段开放一个读取入口，让模组代码能拿到血条对象
 *             去改名、改颜色、改进度。
 * 【所属模块】wither.mixin 原版注入层 —— 本模组对原版的所有改动都从这一层进入。
 * 【协作关系】由 WitherBrain（血条绑定与每帧刷新）与 WitherArmor.updateBossBar 读取；
 *             它自身不调用任何模组代码，纯读取通道。
 * 【关键约束】Mixin 0.8 且无 refmap，所以 @Accessor 的 value 必须直接写 SRG 名；
 *             并且只能访问 WitherBoss 自己声明的成员 —— 写父类 Entity 的成员会让
 *             Mixin 在启动期解析失败并崩游戏。
 */
package com.fusionwither.wither.mixin;

import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 凋零私有血条字段的访问器接口。
 *
 * <p><b>设计要点：</b>原版把血条字段设为私有，模组代码无法直接引用；{@code @Accessor}
 * 让 Mixin 在编译期就把 {@code fusion$getBossEvent()} 实现成对该字段的直接读取，
 * 既不用反射，也不需要往实体上新增任何字段。
 *
 * <p><b>为什么只剩这一个成员：</b>早前这里还有一个用于无敌的访问器，它同时踩了两个坑 ——
 * SRG 名猜错（{@code m_20331_} 实际是 {@code setNoGravity}），以及试图通过 WitherBoss
 * 访问父类 {@code Entity} 的成员。实机启动时直接抛出
 * {@code InvalidAccessorException: No candidates were found matching m_20331_:Z}
 * 崩游戏。Mixin 解析父类成员并不可靠，因此"无敌"改走公开字段
 * {@code Entity.invulnerableTime}（public 字段的引用会被 reobfJar 自动重映射，
 * 不依赖名字猜对，由 WitherBrain 的出生无敌同步逻辑使用），本接口只保留血条这一处。
 *
 * <p><b>生命周期：</b>接口无实例、无状态，每次调用都是对被注入实体的即时字段读取。
 */
@Mixin(WitherBoss.class)
public interface WitherBossAccessor {

    /**
     * 取出凋零的私有 Boss 血条对象。
     *
     * <p><b>调用时机：</b>WitherBrain 首次为这只凋零绑定血条时调用一次，之后复用返回的
     * 实例做改名/改色/改进度，不重复取。血条对象随实体创建而存在、随实体销毁而消亡。
     *
     * <p><b>前提条件：</b>必须在服务端调用；客户端侧没有真实血条实例。
     *
     * <p><b>注入说明：</b>{@code @Accessor("f_31430_")} 注入 {@code WitherBoss} 自己声明的
     * {@code bossEvent} 字段（SRG 名 {@code f_31430_}，经 javap 确证）。因为无 refmap，
     * 这里只能写 SRG 名，写官方名编译期就找不到。
     *
     * @return 该凋零的 Boss 血条，构造时即创建，永不为 null
     */
    @Accessor("f_31430_")
    ServerBossEvent fusion$getBossEvent();
}
