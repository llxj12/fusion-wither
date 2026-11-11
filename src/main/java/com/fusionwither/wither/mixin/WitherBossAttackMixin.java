/*
 * 【文件职责】接管凋灵的"开火权"：把原版唯一能生成凋灵之首的出口堵死，让所有弹幕
 *             都改由本模组的射击系统发出。
 * 【所属模块】wither.mixin 原版注入层。
 * 【协作关系】由 WitherBoss 实例在服务端每帧执行 customServerAiStep 时被触发；
 *             它拦下原版射击，之后真正的射击由 SkullVolley（本模组的射击系统）完成；
 *             位移交给 WitherBossMovementMixin，头部转向与瞄准完全留给原版。
 * 【关键约束】只能堵"生成弹幕"这一个出口，绝不能整体取消 customServerAiStep ——
 *             那会连头部转向一起取消；本模组自己 new 出来的凋灵之首不走这个出口，
 *             因此不会被误伤。
 */
package com.fusionwither.wither.mixin;

import net.minecraft.world.entity.boss.wither.WitherBoss;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让"谁来决定何时开火"这件事完全归本模组所有。
 *
 * <p><b>控制权划分（用户定稿："完全压制原版，只用我方攻击"）：</b>
 * <ul>
 *   <li>三个头各自瞄准 + 头部转向 —— <b>原版</b>。完全不干预：这里包含头部旋转插值，
 *       自己重写一定比原版差。</li>
 *   <li>开火（生成凋灵之首） —— <b>本模组</b>。本类取消 {@code m_31448_}（原版唯一的生成出口）。</li>
 *   <li>"平A / 弹幕压制"的节奏 —— <b>本模组</b>，由 {@link com.fusionwither.wither.SkullVolley} 负责。</li>
 *   <li>出生苏醒（激活动画） —— <b>原版</b>。本类不再干预 {@code getInvulnerableTicks()}，理由见下。</li>
 *   <li>位移 —— 本模组，由 {@link WitherBossMovementMixin} 每帧钉回坐标。</li>
 *   <li>悬停高度 —— 本模组，由 {@code WitherBrain.applyAltitude()} 决定。</li>
 * </ul>
 *
 * <p><b>为什么不整体取消 {@code customServerAiStep}：</b>那样会把头部转向一并取消，
 * 本项目为此走过三次弯路。因此这里选择逐个堵具体出口，而不是让整个 AI 停摆。
 *
 * <p><b>为什么不能再动 {@code getInvulnerableTicks()：</b>本类原先还有一个注入在
 * {@code m_8024_} 开头、对"非二阶段"每 tick 调用 {@code setInvulnerableTicks(0)}，
 * 理由是"压制原版冲锋"。但对 1.20.1 的字节码（{@code javap -c -p}）逐条核对后确认：
 * 这个字段在 1.20.1 里只服务出生苏醒 —— 唯一的写入点是 {@code WitherBoss.m_31506_}，
 * 它执行 {@code setInvulnerableTicks(220)} + 血条进度归零 + 血量设为上限的 1/3，
 * 且只被 {@code WitherSkullBlock}（灵魂沙 + 首级的结构召唤）调用；{@code m_8024_} 里
 * {@code getInvulnerableTicks() > 0} 的分支就是整段苏醒演出（血条按 {@code 1 - t/220}
 * 充能、每 10 tick 回血、倒计时归零时 {@code explode(7.0f, MOB)} + {@code levelEvent(1023)}
 * 吼声），而正常 AI 的 {@code else} 分支从不写这个字段。也就是说"清零"压不掉任何冲锋，
 * 只会把苏醒动画从第 1 tick 掐死。该注入已删除；一阶段"不冲脸"改由
 * {@link WitherBossMovementMixin} 钉坐标实现（原版 {@code aiStep} 的接近加速会在本帧
 * 末尾被落点覆盖），与是否动这个字段无关。
 */
@Mixin(WitherBoss.class)
public abstract class WitherBossAttackMixin {

    /**
     * 取消原版生成凋灵之首的唯一出口。
     *
     * <p><b>调用时机：</b>原版 AI 想发射弹幕时被触发。{@code m_31448_(IDDDZ)V} 是
     * {@code WitherBoss} 的私有方法，字节码里只有它 {@code new WitherSkull}（随后
     * {@code setOwner} / {@code setCharged} / {@code setPos} / {@code Level.addFreshEntity}，
     * 外加 {@code levelEvent(1024)} 射击音效）。它的调用点有三处：{@code customServerAiStep}
     * 里绕自身 ±10/±5/±10 的随机方向充能弹、对已登记目标的补射（30 格 + 视线判定），
     * 以及 {@code performRangedAttack}（{@code m_6504_}）经 {@code m_31457_} 过来的路径。
     *
     * <p><b>为什么注入点选这里而不是 {@code m_6504_}：</b>上一版取消的是
     * {@code performRangedAttack}，但字节码显示它只做一件事 —— 登记/瞄准 0 号头；
     * 三条发射路径里只有一条经过它，所以原版弹幕照旧。堵住唯一出口后，
     * 原版一根凋灵之首都发不出来，而头部转向/瞄准（{@code m_31454_}、{@code m_31510_} 等）
     * 全部保留。之所以必须压制原版射击：本模组自己发弹幕，两边叠加会让弹幕数量翻倍，
     * 而且原版射击不区分"平A"与"弹幕压制"，会盖过我们精心区分的两种节奏。
     *
     * <p><b>不会误伤本模组：</b>{@link com.fusionwither.wither.SkullVolley} 自己
     * {@code new WitherSkull} 并注册，不经过此方法。
     *
     * <p><b>注入说明：</b>{@code @Inject} 打在 {@code m_31448_} 的 HEAD 并可取消；
     * 方法名与描述符用 SRG 版字节码确证（无 refmap，因此不能写官方名）。
     *
     * @param headIndex 正在发射的头的下标（0 为主头，其余为副头）
     * @param x 弹幕生成点的 x 坐标（原版按头的位置算出）
     * @param y 弹幕生成点的 y 坐标
     * @param z 弹幕生成点的 z 坐标
     * @param charged 是否为充能（蓝色）弹幕
     * @param ci 注入回调；调 {@code cancel()} 即跳过原版后续的生成与音效
     */
    @Inject(method = "m_31448_(IDDDZ)V",
            at = @At("HEAD"), cancellable = true)
    private void fusion$suppressVanillaShoot(int headIndex, double x, double y, double z,
                                             boolean charged, CallbackInfo ci) {
        WitherBoss self = (WitherBoss) (Object) this;
        if (self.level().isClientSide()) {
            // 客户端也跑同一套 AI 代码，只在服务端取消，避免两边行为分叉
            return;
        }
        // 开关关闭时保留原版射击，便于对比调试
        if (com.fusionwither.wither.WitherConfig.OUR_ATTACK_ENABLED) {
            ci.cancel();
        }
    }
}
