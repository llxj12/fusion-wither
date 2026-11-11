/*
 * 【文件职责】二阶段凋灵骷髅召唤池：限制同时存活数量、死亡后按节流补召唤。
 * 【所属模块】wither 核心（凋灵辅助能力）。
 * 【协作关系】由二阶段逻辑（PhaseTwoLogic.tick()）每 tick 调用 tickRefill 驱动，
 *             内部通过 aliveCount/spawn 维护归属集合；凋灵死亡时由清理流程调用 clear。
 * 【关键约束】持有关系用"弱引用记录"而不是给骷髅写 NBT 标签 —— 不碰原版实体数据，
 *             也不会与其他模组的标签约定冲突；同时上限必须由补召唤入口控制，避免超编。
 */
package com.fusionwither.wither;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.WitherSkeleton;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 二阶段召唤的凋灵骷髅池，同时存在上限受 {@link WitherConfig#PHASE2_SKELETON_MAX_ALIVE}
 * 约束，死了才补。
 *
 * <p><b>设计要点：</b>用"记录归属"（哪只骷髅属于哪只凋灵）而不是给骷髅打 NBT 标签 ——
 * 不用碰原版实体的数据，也不会和其他模组的标签冲突。归属表和骷髅对象都用
 * {@code WeakHashMap} 承载：凋灵或骷髅被回收后条目自动消失，不会长期占内存，
 * 也不会因为残留强引用而阻止实体卸载。
 *
 * <p><b>生命周期：</b>静态工具类，无实例、无显式初始化；首次为某只凋灵查询归属时
 * 懒创建它的集合，凋灵死亡时由 {@link #clear} 移除条目。
 *
 * <p><b>不变量：</b>同一只凋灵的已存活计数只由 {@link #aliveCount} 现场统计（顺带剔除
 * 失效项），不额外维护计数器，避免出现"计数与实际不符"的漂移。
 */
public final class WitherSkeletonPool {

    /** 每只凋灵 → 它召唤出来的骷髅集合。弱引用键值，随实体卸载自动回收。 */
    private static final Map<WitherBoss, Set<WitherSkeleton>> OWNED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private WitherSkeletonPool() {}

    /**
     * 取（必要时创建）某只凋灵的骷髅归属集合。
     *
     * <p><b>调用时机：</b>{@link #aliveCount} 与 {@link #spawn} 内部调用。
     *
     * <p><b>为什么集合也用 WeakHashMap 包装：</b>骷髅实体被卸载后条目会自动消失，
     * 不必手工清理"已经不存在"的元素。
     *
     * @param wither 归属主体
     * @return 该凋灵的归属集合；调用方需自行 {@code synchronized} 迭代（见 aliveCount）
     */
    private static Set<WitherSkeleton> ownedSet(WitherBoss wither) {
        return OWNED.computeIfAbsent(wither,
                w -> Collections.newSetFromMap(new WeakHashMap<>()));
    }

    /**
     * 统计当前还活着的、属于这只凋灵的骷髅数量。
     *
     * <p><b>调用时机：</b>每次补召唤前（判断还剩多少空位）以及调试信息输出时。
     *
     * <p><b>副作用：</b>遍历时顺手剔除已死亡/已移除的条目，相当于一次惰性清理，
     * 使返回的计数永远是"实际可参战"的数量。
     *
     * @param wither 归属主体
     * @return 存活骷髅数量
     */
    public static int aliveCount(WitherBoss wither) {
        Set<WitherSkeleton> set = ownedSet(wither);
        int n = 0;
        // 集合是同步包装的，迭代期间必须持锁，否则并发增删会抛异常
        synchronized (set) {
            Iterator<WitherSkeleton> it = set.iterator();
            while (it.hasNext()) {
                WitherSkeleton s = it.next();
                // 弱引用可能已被回收成 null，一并视为失效
                if (s == null || !s.isAlive() || s.isRemoved()) {
                    it.remove();
                } else {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * 补召唤一批骷髅，已经达到上限就什么都不做（即"死了才补"）。
     *
     * <p><b>调用时机：</b>由 {@link #tickRefill} 按固定的检查间隔调用。
     *
     * <p><b>副作用：</b>在服务端真实生成实体并加入关卡，同时把目标设为凋灵当前的锁定目标。
     *
     * <p><b>为什么先算 room 再 min：</b>上限是硬约束，请求数量超过空位时只能补满空位，
     * 否则会出现超编的骷髅堆。
     *
     * @param wither 召唤者
     * @param level 所在关卡；非服务端关卡直接放弃（客户端不生成实体）
     * @param wanted 本次希望召唤的数量
     * @return 实际召唤出的数量（受空位与实体创建失败影响，可能少于 wanted）
     */
    public static int spawn(WitherBoss wither, Level level, int wanted) {
        if (!(level instanceof ServerLevel sl)) {
            return 0;
        }
        int alive = aliveCount(wither);
        int room = WitherConfig.PHASE2_SKELETON_MAX_ALIVE - alive;
        if (room <= 0) {
            return 0;
        }
        int count = Math.min(wanted, room);
        Set<WitherSkeleton> set = ownedSet(wither);
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            WitherSkeleton sk = EntityType.WITHER_SKELETON.create(sl);
            if (sk == null) {
                continue;
            }
            // 以凋灵为圆心、2.0~5.0 格半径的随机方位落点，避免全部堆在同一点
            double a = sl.random.nextDouble() * Math.PI * 2.0D;
            double r = 2.0D + sl.random.nextDouble() * 3.0D;
            sk.moveTo(wither.getX() + Math.cos(a) * r,
                    wither.getY(),
                    wither.getZ() + Math.sin(a) * r,
                    sl.random.nextFloat() * 360.0F, 0.0F);

            LivingEntity ownerTarget = wither.getTarget();
            // 直接继承凋灵当前的目标，让骷髅立刻参战；凋灵未索敌时留空，由原版自行找目标
            if (ownerTarget != null) {
                sk.setTarget(ownerTarget);
            }
            sl.addFreshEntity(sk);
            set.add(sk);
            spawned++;
        }
        return spawned;
    }

    /**
     * 二阶段的补召唤节流：每 {@code PHASE2_SKELETON_REFILL_INTERVAL_TICKS} tick 检查一次。
     *
     * <p><b>调用时机：</b>由二阶段逻辑每 tick 调用一次。
     *
     * <p><b>为什么按 {@code tickCount % 间隔} 判断而不是自己记倒计时：</b>用实体自带的
     * tickCount 做相位判断无需额外字段，也不用担心漏 tick 导致相位漂移。
     *
     * <p><b>副作用：</b>满足条件时调用 {@link #spawn} 补一只。
     *
     * @param wither 归属主体
     * @param level 所在关卡
     */
    public static void tickRefill(WitherBoss wither, Level level) {
        if (wither.tickCount % WitherConfig.PHASE2_SKELETON_REFILL_INTERVAL_TICKS != 0) {
            return;
        }
        if (aliveCount(wither) < WitherConfig.PHASE2_SKELETON_MAX_ALIVE) {
            spawn(wither, level, 1);
        }
    }

    /**
     * 凋灵死亡时清空归属记录，避免残留条目继续参与统计。
     *
     * <p><b>调用时机：</b>凋灵死亡/移除的清理流程中。
     *
     * @param wither 归属主体
     */
    public static void clear(WitherBoss wither) {
        OWNED.remove(wither);
    }
}
