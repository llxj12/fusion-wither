/*
 * 【文件职责】凋零所有爆炸与沿途地形破坏的统一出口，以及"哪些方块不许被破坏"的判定。
 * 【所属模块】wither 核心（凋零辅助能力）。
 * 【协作关系】由冲撞行为、二阶段入场演出、冲撞后上升段等调用方分别调用
 *             bigExplosion / crashExplosion / breakAlongCharge；
 *             受保护方块的过滤在 ExplosionEvent.Detonate 里通过 filterDetonation 生效，
 *             破坏白名单/黑名单来自 WitherConfig。
 * 【关键约束】所有爆炸都必须经过本类，让"保护名单"只有一份实现 —— 否则同一条保护规则
 *             会在多个调用点各自漂移。
 */
package com.fusionwither.wither;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 爆炸与地形破坏的统一出口。
 *
 * <p><b>设计要点：</b>所有爆炸都走这里，目的是让「破坏黑名单」只有一个实现 ——
 * 冲撞、二阶段入场、以及冲撞后上升段的破坏都受同一个名单约束。
 *
 * <p><b>为什么不再手工补刀地形：</b>早先的实现既调用 {@code Level.explode}
 * （它自己会破坏地形），又逐方块手工 {@code destroyBlock}，等于每个方块被处理两遍。
 * 现在手工那一遍已删除（原先的 breakAround 方法一并移除），黑名单改由
 * {@code ExplosionEvent.Detonate} 过滤（见 {@link #filterDetonation}），
 * 因此"保护方块"的能力没有丢失，只是换了实现位置。
 *
 * <p><b>例外：</b>冲撞途中的实时破坏（{@link #breakAlongCharge}）是用户明确要求的效果，
 * 它按"凋零本身能破坏的方块"这一条件直接 {@code destroyBlock}，不经过原版爆炸 ——
 * 这是本类里唯一手工破坏方块的地方。
 *
 * <p><b>生命周期：</b>纯静态工具类，无实例；黑名单与前缀表在类初始化时从配置读入一次。
 */
public final class CrashEffects {

    private CrashEffects() {}

    /** 显式禁止破坏的方块 ID 全名（含命名空间）集合，来源于配置，类加载时装载一次。 */
    private static final Set<String> BLACKLIST = new HashSet<>();
    /** 类别兜底保护的子串集合：方块 ID 的命名空间或路径含其中任一子串即受保护。 */
    private static final List<String> PROTECTED_PREFIXES = new ArrayList<>();

    static {
        // 配置里的黑名单按原样匹配完整 ID
        for (String id : WitherConfig.CRASH_BLOCK_BLACKLIST) {
            BLACKLIST.add(id);
        }
        // 前缀统一转小写：匹配时大小写不敏感，避免大小写差异漏保护
        for (String key : WitherConfig.CRASH_PROTECTED_PREFIXES) {
            PROTECTED_PREFIXES.add(key.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 触发一次爆炸。
     *
     * <p><b>调用时机：</b>需要一次性大范围爆炸时调用（例如死亡收尾、入场爆发）。
     *
     * <p><b>副作用：</b>在凋零当前坐标调用原版 {@code Level.explode}，产生伤害与击退；
     * {@code destructive} 为 true 时还会破坏地形。
     *
     * @param wither 爆炸的制造者（用于伤害归属与免疫判定）
     * @param level 所在关卡；客户端直接返回，避免客户端重复爆炸
     * @param power 爆炸威力（原版 explode 的 radius 语义）
     * @param destructive 是否破坏地形（false 时只做伤害与击退）
     */
    public static void bigExplosion(WitherBoss wither, Level level, float power, boolean destructive) {
        if (level.isClientSide()) {
            return;
        }
        level.explode(
                wither,
                wither.getX(),
                wither.getY(),
                wither.getZ(),
                power,
                destructive ? Level.ExplosionInteraction.MOB : Level.ExplosionInteraction.NONE
        );
    }

    /**
     * 冲撞命中点的爆炸（威力来自配置）。
     *
     * <p><b>调用时机：</b>冲撞撞到目标或方块、需要结算冲击时调用一次。
     *
     * <p><b>副作用：</b>原版爆炸产生伤害与击退；是否破地形由
     * {@link WitherConfig#CRASH_BREAK_BLOCKS} 决定，威力由
     * {@link WitherConfig#CRASH_EXPLOSION_POWER} 决定。
     *
     * <p><b>为什么单独开一个方法而不是复用 bigExplosion：</b>冲撞的威力与是否破地形都是
     * 可独立配置的，且默认不破地形，与死亡演出那类"必破"的爆炸语义不同。
     *
     * @param wither 冲撞的制造者
     * @param level 所在关卡；客户端直接返回
     */
    public static void crashExplosion(WitherBoss wither, Level level) {
        if (level.isClientSide()) {
            return;
        }
        level.explode(wither, wither.getX(), wither.getY(), wither.getZ(),
                WitherConfig.CRASH_EXPLOSION_POWER,
                WitherConfig.CRASH_BREAK_BLOCKS
                        ? Level.ExplosionInteraction.MOB
                        : Level.ExplosionInteraction.NONE);
    }

    /**
     * 爆炸前的方块过滤：从受影响方块列表里剔除受保护方块。
     *
     * <p><b>调用时机：</b>由 {@code ExplosionEvent.Detonate} 在爆炸实际生效前调用。
     * 这是手工破坏被移除之后，保护方块的唯一实现位置 —— 原版爆炸负责实际破坏，
     * 这里只负责把不该破坏的方块从列表里拿掉，因此保护能力没有丢失。
     *
     * <p><b>副作用：</b>原地修改传入的列表（removeIf），不改方块本身；必须在事件回调里
     * 对同一个列表对象操作，事件才会生效。
     *
     * @param affected 本次爆炸将影响的方块坐标列表，由事件提供
     * @param level 所在关卡，用于取每个坐标的方块状态
     */
    public static void filterDetonation(List<BlockPos> affected, Level level) {
        affected.removeIf(pos -> isProtected(level.getBlockState(pos)));
    }

    /**
     * 冲撞途中实时破坏沿途方块（实机反馈新增）。
     *
     * <p><b>调用时机：</b>冲撞冲锋期间由冲撞行为每 tick 调用。
     *
     * <p><b>用户要求：</b>"冲撞时直接破坏路经上的、凋零可以破坏的方块"，让高速冲撞有
     * "撞穿一切"的实感，而不是撞墙就停。
     *
     * <p><b>副作用：</b>直接 {@code destroyBlock} 沿路方块（不掉落物品），会改变地形；
     * 这是本模组里唯一绕开原版爆炸的手工破坏路径，因此三重过滤都不能省：只破凋零本身能破的
     * 方块（{@code getDestroySpeed >= 0} 排除基岩类，且排除流体）、受 F-09 黑名单与前缀兜底
     * 约束（箱子、床、门、工作站点等不破）、半径由
     * {@link WitherConfig#CRASH_CHARGE_BREAK_RADIUS} 控制而只覆盖身体周围。
     *
     * @param wither 冲撞的制造者
     * @param level 所在关卡；客户端直接返回 0
     * @return 本次破坏的方块数量（供调试）
     */
    public static int breakAlongCharge(WitherBoss wither, Level level) {
        if (level.isClientSide()) {
            return 0;
        }
        double r = WitherConfig.CRASH_CHARGE_BREAK_RADIUS;
        // 用 BlockPos.containing 把实体坐标取整为所在方块；不要改用带 0.2 格 y 偏移的
        // 位置取整方式，否则扫描中心整体抬高，破坏范围会偏上
        BlockPos center = BlockPos.containing(wither.getX(), wither.getY(), wither.getZ());
        int rr = (int) Math.ceil(r);
        int broken = 0;
        // 竖直方向只取脚下一格到身体上方两格，避免把地下掏空或打到远处天花板
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    // 用水平距离平方限制成圆柱体，避免炸出一个方盒状空洞
                    if (dx * dx + dz * dz > r * r) {
                        continue;
                    }
                    BlockPos p = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(p);
                    // 空气与流体不处理，避免无意义写入
                    if (state.isAir() || !state.getFluidState().isEmpty()) {
                        continue;
                    }
                    // 凋零本身无法破坏的方块（基岩等）永不破坏
                    if (state.getDestroySpeed(level, p) < 0.0F) {
                        continue;
                    }
                    // F-09 保护名单
                    if (isProtected(state)) {
                        continue;
                    }
                    // 第二参数 false：不产生掉落物，避免冲撞刷出大量物品
                    level.destroyBlock(p, false, wither);
                    broken++;
                }
            }
        }
        return broken;
    }

    /**
     * 判断该方块是否受保护（不可被冲撞/入场爆炸破坏）。
     *
     * <p><b>调用时机：</b>{@link #filterDetonation} 与 {@link #breakAlongCharge} 的过滤环节。
     *
     * <p><b>两级判定顺序：</b>先查显式黑名单（精确 ID），再用前缀兜底 —— 后者保证配置漏写
     * 某个方块时，同族方块仍被保护；前缀同时检查命名空间，因此 {@code somemod:xxx_bed}
     * 这类模组方块也会被保护。
     *
     * @param state 待判定的方块状态
     * @return true 表示该方块受保护，不应被破坏
     */
    public static boolean isProtected(BlockState state) {
        Block b = state.getBlock();
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(b);
        // 未注册/无 ID 的方块（理论上极少）无法比对名单，按不保护处理
        if (id == null) {
            return false;
        }
        // 1) 显式名单
        if (BLACKLIST.contains(id.toString())) {
            return true;
        }
        // 2) 类别兜底：命名空间或路径含受保护子串（F-09）。
        //    同时检查命名空间，因此 somemod:xxx_bed 这类模组方块也会被保护。
        String ns = id.getNamespace().toLowerCase(Locale.ROOT);
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (String key : PROTECTED_PREFIXES) {
            if (path.contains(key) || ns.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
