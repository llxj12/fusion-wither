/*
 * 【文件职责】二阶段行为池 · 行为A「压制弹幕」：在技能窗口内让三个头各自独立瞄准、独立开火，并维持低速度位移。
 * 【所属模块】wither.behavior 行为池。
 * 【协作关系】继承 BaseBehavior，由 WitherBrain 二阶段派发逻辑按权重选中后 begin()，每帧 tick()；
 *             射击交给 WitherBrain.fireReadyHeads（三头独立冷却），位移交给 WitherBrain 的坐标所有权接口（不再自推）。
 * 【关键约束】技能窗口时长（BARRAGE_DURATION_*，50~100t）必须容纳至少两个射击周期，否则会出现「技能触发了却没发射」。
 */
package com.fusionwither.wither.behavior;

import com.fusionwither.wither.WitherConfig;
import net.minecraft.world.entity.LivingEntity;

/**
 * 行为池 · 行为 A：<b>弹幕压制</b>。
 *
 * <p>三头各自独立瞄准（原版 Java 凋零式）+ 低速度位移，持续随机 {@code BARRAGE_DURATION_MIN_TICKS ~ MAX_TICKS}
 * （{@link WitherConfig#BARRAGE_DURATION_MIN_TICKS} 50t ~ {@link WitherConfig#BARRAGE_DURATION_MAX_TICKS} 100t）。
 * 位移速度很低，给玩家绕后或找掩体的空间。
 *
 * <p><b>窗口时长为什么至少 50t：</b>三头独立开火的周期是主头 {@code BARRAGE_MAIN_SHOT_INTERVAL_TICKS}(40t)、
 * 副头 {@code BARRAGE_SIDE_SHOT_INTERVAL_*}(40~60t)。若窗口短于一个完整射击周期，
 * 技能常常「触发了却一发没射」就结束。50t 保证窗口内至少能容纳两次主头射击、副头至少一次。
 *
 * <p><b>射击方式（实机反馈重构）：</b>原先这里是「三颗头同一 tick 齐射」，每隔固定间隔一次。
 * 现在与一阶段的压制弹幕统一走 {@link com.fusionwither.wither.WitherBrain#fireReadyHeads} ——
 * <b>三颗头各持一个独立冷却</b>（主头 40t、副头 40~60t），到点各自开火。
 * 于是两阶段的「弹幕压制」是同一套语义，不会再出现一边齐射、一边独立的漂移。
 *
 * <p><b>权重：</b>由 WitherBrain 按 {@code WEIGHT_BARRAGE_NORMAL}(50) / {@code WEIGHT_BARRAGE_HIGH}(30)
 * 与冲撞加权随机竞争；{@code desire} ≥ 0.5（血量劣势更大）时用 HIGH 权重组。
 */
public final class BarrageBehavior extends BaseBehavior {

    /** 本轮技能窗口总时长（tick），onBegin 时从配置随机取；由 elapsed 与它比较决定结束。 */
    private int duration = 0;
    /** 已推进的 tick 数，onTick 每帧自增；仅本类使用，不与 WitherBrain 的射击冷却计时共用。 */
    private int elapsed = 0;

    /** 行为名（日志与调试命令用）。 */
    @Override
    public String name() {
        return "弹幕压制";
    }

    /** 开始：随机本轮窗口时长，并把 elapsed 清零、三头冷却错峰。 */
    @Override
    protected void onBegin() {
        duration = randInt(WitherConfig.BARRAGE_DURATION_MIN_TICKS,
                WitherConfig.BARRAGE_DURATION_MAX_TICKS);
        elapsed = 0;
        // 三颗头的冷却用随机初相位错峰 —— 否则进入行为那一帧三颗头可能同时为 0，
        // 当帧就是「三个头一起发射」（正是一阶段实测反馈要修的症状）。
        brain().staggerHeadShotCds(WitherConfig.BARRAGE_MAIN_SHOT_INTERVAL_TICKS,
                WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS);
    }

    /** 每帧推进：累加 elapsed，播放行为标识粒子，按各头冷却开火；目标中途消失则停住位移（空转等待结束）。 */
    @Override
    protected void onTick() {
        elapsed++;
        LivingEntity t = target();
        if (t == null) {
            // 目标在技能中途消失（死亡 / 切创造）时不再有任何位移意图 —— 表现为原地悬停。
            brain().stopMoving();
            return;
        }

        // 行为标识粒子（实机反馈：弹幕压制与空窗期普攻外观无区别，需要视觉区分）
        com.fusionwither.wither.VisualFeedback.barrageActive(brain().wither(), brain().level());

        // 【控制权划分】位移已全部交给 WitherBrain 的 moveToward() ——
        // 它按「阶段 × 当前行为」决定期望距离与高度，并直接纠正实体坐标。
        // 本行为<b>不再</b>自行推动凋零：早先这里用 brain().push() 会与 moveToward
        // 抢速度向量，是本项目多轮失败的根源。压制时的距离差异（一阶段接近 / 二阶段稍远）
        // 由 moveToward 的 desiredDistance() 依据当前行为名判定。

        // 压制输出：三颗头各按自己的冷却开火（原版 Java 凋零式）。
        // 计时由 WitherBrain.tick() 每帧统一扣减，本行为不做任何本地计时 ——
        // 若这里再自行计时，就会与「三头独立冷却」形成两套时间语义而漂移。
        brain().fireReadyHeads(t,
                WitherConfig.BARRAGE_MAIN_SHOT_INTERVAL_TICKS,
                WitherConfig.BARRAGE_MAIN_SHOT_INTERVAL_TICKS,
                WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS,
                WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS,
                WitherConfig.SKULL_BLACK_DAMAGE);
    }

    /** 窗口时长用尽即结束；结束后由 WitherBrain 上弹幕冷却并选下一个行为。 */
    @Override
    protected boolean checkFinished() {
        return elapsed >= duration;
    }
}
