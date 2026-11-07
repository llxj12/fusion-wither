/*
 * 【文件职责】凋零的视觉/听觉反馈总集：把"它要干什么"用粒子与音效提前告诉玩家。
 * 【所属模块】wither 核心（凋零辅助能力）。
 * 【协作关系】由各行为与阶段逻辑在关键时刻调用（阶段切换、狂暴切换、二阶段入场、
 *             冲撞蓄力/冲锋/命中、弹幕压制期间）；调用方在服务端每帧驱动。
 * 【关键约束】所有粒子/音效只在服务端发出（sendParticles/playSound 由服务端广播），
 *             因此这里用 ServerLevel 判断；音效位置必须用实体当前坐标，
 *             不可换成会让 y 轴偏移的取整位置。
 */
package com.fusionwither.wither;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 视觉/听觉反馈的门面类。
 *
 * <p><b>设计要点：</b>验收标准第 1 条要求「玩家血量跨越 50% 时行为模式切换且有视觉提示」，
 * 这个类就是那个提示；二阶段入场也需要"护甲闪光"演出。把这些表现集中在一处，
 * 行为逻辑只需要一行调用，不必各自拼粒子参数，避免同类反馈在不同行为里长得不一样。
 *
 * <p><b>生命周期：</b>纯静态工具类，无实例、无状态；每次调用即发一次效果。
 *
 * <p><b>通用约定：</b>方法一律接收 {@code Level}，内部用 {@code instanceof ServerLevel}
 * 做隔离 —— 客户端不自行发粒子和音效，由服务端广播到达。
 */
public final class VisualFeedback {

    private VisualFeedback() {}

    /**
     * 攻击欲望切换：红色粒子环 + 音效，让玩家知道凋零"上头了"或"冷静了"。
     *
     * <p><b>调用时机：</b>目标血量跨越阈值导致攻击欲望变化的那一刻，只调一次。
     *
     * <p><b>副作用：</b>向附近玩家广播两组粒子并播放一声环境音。
     *
     * @param wither 目标凋零
     * @param level 所在关卡；非服务端直接返回
     */
    public static void desireSwitched(WitherBoss wither, Level level) {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        double x = wither.getX();
        double y = wither.getY() + 1.0D;
        double z = wither.getZ();
        sl.sendParticles(ParticleTypes.ANGRY_VILLAGER, x, y + 1.0D, z, 12, 0.8D, 0.6D, 0.8D, 0.02D);
        sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 20, 1.2D, 1.0D, 1.2D, 0.01D);
        level.playSound(null, x, y, z, SoundEvents.WITHER_AMBIENT, SoundSource.HOSTILE, 1.5F, 0.7F);
    }

    /**
     * 二阶段入场演出：怒吼 + 护甲闪光 + 闪电。
     *
     * <p><b>调用时机：</b>凋零进入二阶段的那一刻（入场坠落演出开始时），只调一次。
     *
     * <p><b>副作用：</b>广播成环的火焰粒子、灵魂火、爆炸发射器粒子，并播放两声吼叫；
     * 其中 {@code WITHER_SPAWN} 音调压到 0.6 制造"更低沉"的压迫感。
     *
     * @param wither 目标凋零
     * @param level 所在关卡；非服务端直接返回
     */
    public static void phaseTwoEntry(WitherBoss wither, Level level) {
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        double x = wither.getX();
        double y = wither.getY() + 1.0D;
        double z = wither.getZ();
        // 护甲闪光：灵魂火 + 火焰，绕身体一圈
        for (int i = 0; i < 40; i++) {
            double a = i / 40.0D * Math.PI * 2.0D;
            sl.sendParticles(ParticleTypes.FLAME, x + Math.cos(a) * 1.6D, y, z + Math.sin(a) * 1.6D,
                    1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y, z, 60, 1.5D, 1.5D, 1.5D, 0.05D);
        sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 3, 1.0D, 1.0D, 1.0D, 0.0D);
        level.playSound(null, x, y, z, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 3.0F, 0.6F);
        level.playSound(null, x, y, z, SoundEvents.WITHER_HURT, SoundSource.HOSTILE, 2.0F, 0.5F);
    }

    /**
     * 护甲挡下弹射物时的火花。
     *
     * <p><b>调用时机：</b>ProjectileImpactEvent 取消一次命中之后立即调用，
     * 位置就是弹射物的命中点。
     *
     * <p><b>副作用：</b>服务端广播火花粒子，并播放一声高音调铁砧落地音 —— 用金属撞击声
     * 表达"被硬壳弹开"，与受伤音效区分开，让玩家明白打不进去而不是打不疼。
     *
     * @param level 所在关卡
     * @param x 命中点 x
     * @param y 命中点 y
     * @param z 命中点 z
     */
    public static void armorDeflected(Level level, double x, double y, double z) {
        if (level instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.CRIT, x, y, z, 8, 0.3D, 0.3D, 0.3D, 0.1D);
        }
        level.playSound(null, x, y, z, SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.6F, 1.8F);
    }

    /**
     * 冲撞起步的提示音（给玩家反应窗口的听觉线索）。
     *
     * <p><b>调用时机：</b>冲撞蓄力开始时播放一次，音调压低表示"要来了"。
     *
     * <p><b>副作用：</b>在凋零当前坐标播放一声射击音。
     *
     * @param wither 目标凋零
     * @param level 所在关卡
     */
    public static void crashWindup(WitherBoss wither, Level level) {
        level.playSound(null, wither.getX(), wither.getY(), wither.getZ(),
                SoundEvents.WITHER_SHOOT, SoundSource.HOSTILE, 2.0F, 0.6F);
    }

    /** 冲撞蓄力期间每 tick 的粒子（让"要冲了"看得见）。
     *  <p>原实现在蓄力期间没有任何表现，玩家感觉"没有动画就冲了"，因此补上这层持续提示。</p>*/
    public static void crashWindupTick(WitherBoss wither, Level level) {
        if (level instanceof ServerLevel sl) {
            double x = wither.getX();
            double y = wither.getY() + 1.0D;
            double z = wither.getZ();
            // 面向方向的前方聚集红色尘土，像在"蓄势"
            Vec3 look = wither.getLookAngle();
            sl.sendParticles(ParticleTypes.CRIT,
                    x + look.x * 1.5D, y, z + look.z * 1.5D, 6, 0.3D, 0.3D, 0.3D, 0.1D);
            sl.sendParticles(ParticleTypes.SMOKE, x, y, z, 4, 0.6D, 0.4D, 0.6D, 0.01D);
        }
    }

    /**
     * 冲撞冲锋期间每 tick 的尾迹（让高速移动看得见）。
     *
     * <p><b>调用时机：</b>冲撞已经起步、正在高速位移的每一 tick 调用。
     *
     * <p><b>副作用：</b>只发粒子不播音效 —— 逐 tick 播音效会糊成噪音；
     * 用爆炸粒子 + 大烟雾构成可追踪的尾迹。
     *
     * @param wither 目标凋零
     * @param level 所在关卡；非服务端不发粒子
     */
    public static void crashChargeTrail(WitherBoss wither, Level level) {
        if (level instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.EXPLOSION,
                    wither.getX(), wither.getY() + 1.0D, wither.getZ(), 2, 0.4D, 0.4D, 0.4D, 0.0D);
            sl.sendParticles(ParticleTypes.LARGE_SMOKE,
                    wither.getX(), wither.getY() + 1.0D, wither.getZ(), 8, 0.5D, 0.5D, 0.5D, 0.02D);
        }
    }

    /** 弹幕压制期间的视觉标识（让该行为与空窗期普攻可分辨）。
     *  <p>用户反馈"弹幕压制和普通游走普攻几乎没有区别"，因此给出持续可辨认的标识。</p>*/
    public static void barrageActive(WitherBoss wither, Level level) {
        if (level instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    wither.getX(), wither.getY() + 2.0D, wither.getZ(), 3,
                    0.5D, 0.4D, 0.5D, 0.01D);
        }
    }

    /**
     * 一阶段狂暴模式的持续血色光环（用户定稿新增）。
     *
     * <p><b>调用时机：</b>一阶段进入狂暴模式后每 tick 调用，直到阶段切换。
     *
     * <p><b>用途：</b>狂暴模式与二阶段<b>都是"贴脸"</b>，仅靠行为难以分辨。持续的血红色
     * 粒子让玩家一眼看出"它进入狂暴了"，同时颜色与二阶段的护甲闪光不同，不会混淆。
     *
     * <p><b>副作用：</b>按配置的粒子数量广播血色尘土，并配少量烟雾强化"发热/暴怒"的观感。
     *
     * @param wither 目标凋零
     * @param level 所在关卡；非服务端不发粒子
     */
    public static void rageAura(WitherBoss wither, Level level) {
        if (level instanceof ServerLevel sl) {
            int n = WitherConfig.P1_RAGE_PARTICLE_COUNT;
            // 血红色：用 DUST 并带颜色数据（红色）
            sl.sendParticles(new net.minecraft.core.particles.DustParticleOptions(
                            new org.joml.Vector3f(0.85F, 0.05F, 0.05F), 1.6F),
                    wither.getX(), wither.getY() + 1.5D, wither.getZ(), n,
                    0.7D, 0.8D, 0.7D, 0.02D);
            // 配合少量烟雾，强化"发热/暴怒"的观感
            sl.sendParticles(ParticleTypes.LARGE_SMOKE,
                    wither.getX(), wither.getY() + 1.0D, wither.getZ(), 2,
                    0.6D, 0.6D, 0.6D, 0.01D);
        }
    }

    /**
     * 冲撞命中的爆炸音效。
     *
     * <p><b>调用时机：</b>冲撞撞上目标/方块、爆炸结算时调用一次。
     *
     * <p><b>为什么不在这里 {@code level.explode}：</b>实际伤害与地形破坏统一走
     * {@link CrashEffects}，本类只负责"响"，避免出现两处爆炸实现行为漂移。
     *
     * <p><b>副作用：</b>播放一声通用爆炸音（音调 0.7 压低，与普通爆炸区分）。
     *
     * @param wither 目标凋零
     * @param level 所在关卡
     */
    public static void crashImpact(WitherBoss wither, Level level) {
        level.playSound(null, wither.getX(), wither.getY(), wither.getZ(),
                SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE, 2.0F, 0.7F);
    }
}
