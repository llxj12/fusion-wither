/*
 * 【文件职责】让本模组发射的凋零之首按配置取值：伤害不再用原版硬编码的 8 点，
 *             蓝色弹幕按配置附加凋零效果。
 * 【所属模块】wither.mixin 原版注入层。
 * 【协作关系】由凋零之首的命中处理 {@code m_5790_(EntityHitResult)} 触发；
 *             伤害值/蓝色标记都来自 SkullVolley（它生成弹幕时登记），配置值来自
 *             WitherConfig；本类只读取这两者，不做登记。
 * 【关键约束】改写必须能被"非本模组弹幕"绕过 —— 查不到 SkullVolley 登记标记的弹幕
 *             一律走原版数值，否则会误改原版/其他模组发射的凋零之首。
 */
package com.fusionwither.wither.mixin;

import com.fusionwither.wither.SkullVolley;
import com.fusionwither.wither.WitherConfig;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.WitherSkull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把凋零之首的命中效果从原版写死的数值，改成可配置的数值。
 *
 * <p><b>为什么需要这个 Mixin：</b>原版 {@code WitherSkull.m_5790_} 里的伤害是字面量常量
 * （字节码 {@code ldc 8.0f}），不读任何字段，所以 {@link WitherConfig#SKULL_BLACK_DAMAGE}、
 * {@link WitherConfig#SKULL_BLUE_DAMAGE}、{@link WitherConfig#IDLE_ATTACK_DAMAGE}
 * 在改造前永远不生效；蓝色弹幕的凋零等级与时长同样是写死的 II 级 / 40 tick。
 *
 * <p><b>作用范围被严格限制：</b>只有 {@link SkullVolley} 发出、并在
 * {@link SkullVolley#damageOf} 里登记过伤害值的弹幕才会被改写。其他模组或原版凋零自己
 * 发射的弹幕查不到标记，走原版 8.0f 不变 —— 这是"只改自己的弹幕"的保证。
 *
 * <p><b>为什么用 {@code @Redirect} 而不是 {@code @Overwrite}：</b>
 * {@code m_5790_} 里还有击退、5.0 治疗分支、{@code isDangerous} 判断与方块破坏强度逻辑，
 * 整体覆写会一并替换掉，既超出"改伤害"的范围，也容易与其他模组冲突。
 *
 * <p>方法名与描述符均以 SRG 版字节码（{@code javap -c -p}）确证。
 */
@Mixin(WitherSkull.class)
public abstract class WitherSkullMixin {

    /**
     * 把原版硬编码的伤害换成该弹幕登记的伤害值。
     *
     * <p><b>调用时机：</b>凋零之首命中实体、原版走到"结算伤害"那一步时被触发。
     *
     * <p><b>注入说明：</b>{@code @Redirect} 替换的是原版对
     * {@code Entity.m_6469_(DamageSource, float)Z}（即 {@code hurt}）的调用；
     * 原版此处压栈的是 {@code 8.0f}，由本方法改写成标记值。
     *
     * @param target 被命中的实体（原版调用的接收者）
     * @param source 本弹幕的伤害源，含发射者与伤害类型
     * @param original 原版要用的伤害值 8.0f
     * @return 命中的处理结果；未登记过标记的弹幕原样返回 {@code original}
     */
    @Redirect(
            method = "m_5790_(Lnet/minecraft/world/phys/EntityHitResult;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;m_6469_(Lnet/minecraft/world/damagesource/DamageSource;F)Z"
            )
    )
    private boolean fusion$customSkullDamage(Entity target, DamageSource source, float original) {
        WitherSkull self = (WitherSkull) (Object) this;
        Float marked = SkullVolley.damageOf(self);
        // 未登记的弹幕（原版或其他模组发的）保持 8.0f 不变
        return target.hurt(source, marked != null ? marked : original);
    }

    /**
     * 给本模组的蓝色弹幕按配置附加凋零效果。
     *
     * <p><b>调用时机：</b>同一次命中处理的末尾（TAIL），即原版结算伤害、附加自身效果之后。
     *
     * <p><b>为什么用 TAIL 追加而不是替换原版的构造调用：</b>替换需要重写整段分支，
     * 而追加只需在方法末尾补一次；原版给"危险"凋零之首的是固定 II 级、固定 40 tick，
     * 这里对标记为蓝的弹幕再补一次按配置的效果，于是
     * {@link WitherConfig#SKULL_BLUE_WITHER_LEVEL} 与
     * {@link WitherConfig#SKULL_BLUE_WITHER_DURATION_TICKS} 得以生效。
     * 等级相同时 {@code addEffect} 的行为是刷新时长而非叠加等级。
     *
     * @param hitResult 本次命中结果；从中取被命中实体
     * @param ci 注入回调；本注入不取消原版行为
     */
    @Inject(
            method = "m_5790_(Lnet/minecraft/world/phys/EntityHitResult;)V",
            at = @At("TAIL")
    )
    private void fusion$blueWitherEffect(net.minecraft.world.phys.EntityHitResult hitResult, CallbackInfo ci) {
        WitherSkull self = (WitherSkull) (Object) this;
        // 只在服务端施加效果（效果同步由原版负责），且只对自己发的蓝弹生效
        if (!self.level().isClientSide()
                && SkullVolley.isBlue(self)
                && hitResult.getEntity() instanceof net.minecraft.world.entity.LivingEntity living) {
            living.addEffect(new MobEffectInstance(
                    MobEffects.WITHER,
                    WitherConfig.SKULL_BLUE_WITHER_DURATION_TICKS,
                    WitherConfig.SKULL_BLUE_WITHER_LEVEL));
        }
    }
}
