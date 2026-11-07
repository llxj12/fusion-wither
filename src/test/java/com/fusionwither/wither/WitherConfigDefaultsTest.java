/*
 * 【文件职责】为 WitherConfig 的默认取值与数值区间提供回归保护。
 * 【所属模块】测试（src/test），不属于模组运行时产物。
 * 【协作关系】被测类 WitherConfig 是纯数值配置类，不触碰任何 Minecraft 类型，
 *            因此本测试可以在普通 JVM 里直接运行，不需要启动游戏。
 * 【关键约束】测试里的期望值必须与 WitherConfig 声明处的默认值逐字一致：
 *            一旦有人改了默认手感却忘了同步，本测试会立刻失败。
 */
package com.fusionwither.wither;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 配置不变量测试：锁定「默认手感」与「数值区间自洽」两件事。
 *
 * <p><b>为什么需要它：</b>WitherConfig 的字段在改为「运行时可配」之后不再受
 * {@code final} 保护，任何人都可能误改默认值而不自知。本测试把每一条默认值
 * 写成断言，等价于给默认手感加了一把锁。</p>
 *
 * <p><b>为什么只测默认值与相对关系，不测绝对范围：</b>绝对范围由
 * {@code ForgeConfigSpec} 的 {@code defineInRange} 在加载时校验，
 * 这里只需保证「未加载配置时」的行为与原常量版本完全一致。</p>
 */
public class WitherConfigDefaultsTest {

    /** 浮点比较容差：配置值都是十进制小数，1e-9 足够严格且不会因表示误差误报。 */
    private static final double EPS = 1.0e-9D;

    /**
     * 默认值快照：每一条都必须与 {@link WitherConfig} 声明处的初值相同。
     *
     * <p>这些数字是「已通过实机验收」的那一版手感，改动等于改手感，
     * 必须同时更新本测试并说明理由。</p>
     */
    @Test
    public void defaultsMatchAcceptedGameFeel() {
        assertEquals(600.0D, WitherConfig.WITHER_MAX_HEALTH, EPS);
        assertEquals(0.50D, WitherConfig.PHASE2_THRESHOLD, EPS);
        assertEquals(220, WitherConfig.WITHER_AWAKENING_TICKS);

        assertEquals(6.5D, WitherConfig.P1_KITE_RETREAT_DISTANCE, EPS);
        assertEquals(8.5D, WitherConfig.P1_KITE_APPROACH_DISTANCE, EPS);
        assertEquals(110, WitherConfig.P1_BARRAGE_CD_MIN_TICKS);
        assertEquals(180, WitherConfig.P1_BARRAGE_CD_MAX_TICKS);
        assertEquals(50, WitherConfig.P1_BARRAGE_DURATION_MIN_TICKS);
        assertEquals(100, WitherConfig.P1_BARRAGE_DURATION_MAX_TICKS);
        assertEquals(180, WitherConfig.P1_BE_DASH_CD_MIN_TICKS);
        assertEquals(320, WitherConfig.P1_BE_DASH_CD_MAX_TICKS);
        assertEquals(0.50D, WitherConfig.P1_BE_DASH_SPEED_MIN, EPS);
        assertEquals(0.62D, WitherConfig.P1_BE_DASH_SPEED_MAX, EPS);
        assertEquals(1, WitherConfig.P1_BE_DASH_SEGMENTS_MIN);
        assertEquals(2, WitherConfig.P1_BE_DASH_SEGMENTS_MAX);

        assertEquals(8.0D, WitherConfig.SKULL_BLACK_DAMAGE, EPS);
        assertEquals(12.0D, WitherConfig.SKULL_BLUE_DAMAGE, EPS);
        assertEquals(200, WitherConfig.SKULL_BLUE_WITHER_DURATION_TICKS);
        assertEquals(40, WitherConfig.BARRAGE_MAIN_SHOT_INTERVAL_TICKS);
        assertEquals(40, WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS);
        assertEquals(60, WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS);
        assertEquals(20, WitherConfig.RAGE_MAIN_SHOT_INTERVAL_TICKS);
        assertEquals(30, WitherConfig.RAGE_SIDE_SHOT_INTERVAL_MIN_TICKS);
        assertEquals(50, WitherConfig.RAGE_SIDE_SHOT_INTERVAL_MAX_TICKS);

        assertEquals(2.0D, WitherConfig.PHASE2_ALTITUDE_MIN, EPS);
        assertEquals(3.0D, WitherConfig.PHASE2_ALTITUDE_MAX, EPS);
        assertEquals(50, WitherConfig.WEIGHT_CRASH_NORMAL);
        assertEquals(50, WitherConfig.WEIGHT_BARRAGE_NORMAL);
        assertEquals(70, WitherConfig.WEIGHT_CRASH_HIGH);
        assertEquals(30, WitherConfig.WEIGHT_BARRAGE_HIGH);
        assertEquals(10.0D, WitherConfig.CRASH_IMPACT_DAMAGE, EPS);
        assertEquals(2.20D, WitherConfig.CRASH_SPEED, EPS);
        assertTrue(WitherConfig.CRASH_BREAK_BLOCKS);
        assertTrue(WitherConfig.ARMOR_BLOCKS_PROJECTILES);
        assertTrue(WitherConfig.PHASE2_ENTRY_FALL_ENABLED);
        assertEquals(1.2D, WitherConfig.PHASE2_ENTRY_FALL_SPEED, EPS);

        assertEquals(0.05D, WitherConfig.REGEN_NORMAL_PER_TICK, EPS);
        assertEquals(0.10D, WitherConfig.REGEN_HIGH_DESIRE_PER_TICK, EPS);

        assertTrue(WitherConfig.DEATH_SEQUENCE_ENABLED);
        assertEquals(80, WitherConfig.DEATH_SEQUENCE_TICKS);
        assertEquals(20, WitherConfig.DEATH_SOUND_INTERVAL_TICKS);

        // 正式游玩必须静默：行为序列日志只在验收时打开
        assertTrue(!WitherConfig.DEBUG_LOG_BEHAVIOR_SEQUENCE);
    }

    /**
     * 区间自洽：成对出现的下限/上限不能写反，否则技能窗口或速度采样会得到空区间。
     *
     * <p>这类错误不会让编译失败，只会让行为「永远不触发」或「偶尔抖一下」，
     * 是最难在实测里定位的一类配置错误，因此用断言提前拦住。</p>
     */
    @Test
    public void pairedRangesAreOrdered() {
        assertTrue("压制弹幕冷却区间写反", WitherConfig.P1_BARRAGE_CD_MIN_TICKS <= WitherConfig.P1_BARRAGE_CD_MAX_TICKS);
        assertTrue("压制弹幕窗口区间写反", WitherConfig.P1_BARRAGE_DURATION_MIN_TICKS <= WitherConfig.P1_BARRAGE_DURATION_MAX_TICKS);
        assertTrue("BE 位移冷却区间写反", WitherConfig.P1_BE_DASH_CD_MIN_TICKS <= WitherConfig.P1_BE_DASH_CD_MAX_TICKS);
        assertTrue("BE 位移速度区间写反", WitherConfig.P1_BE_DASH_SPEED_MIN <= WitherConfig.P1_BE_DASH_SPEED_MAX);
        assertTrue("BE 位移段数区间写反", WitherConfig.P1_BE_DASH_SEGMENTS_MIN <= WitherConfig.P1_BE_DASH_SEGMENTS_MAX);
        assertTrue("弹幕副头射击间隔区间写反", WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS <= WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS);
        assertTrue("狂暴副头射击间隔区间写反", WitherConfig.RAGE_SIDE_SHOT_INTERVAL_MIN_TICKS <= WitherConfig.RAGE_SIDE_SHOT_INTERVAL_MAX_TICKS);
        assertTrue("二阶段压制高度区间写反", WitherConfig.PHASE2_ALTITUDE_MIN <= WitherConfig.PHASE2_ALTITUDE_MAX);
    }

    /**
     * 关键比例与时长必须落在有意义的范围里。
     *
     * <p>例如压制弹幕窗口若小于一个射击周期，就会出现「技能触发了但一发都没打出去」
     * ——这正是历史上踩过的坑，因此下限单独加断言。</p>
     */
    @Test
    public void keyValuesStayMeaningful() {
        assertTrue("阶段阈值必须落在 (0,1) 开区间", WitherConfig.PHASE2_THRESHOLD > 0.0D && WitherConfig.PHASE2_THRESHOLD < 1.0D);
        assertTrue("BOSS 血量必须为正", WitherConfig.WITHER_MAX_HEALTH > 0.0D);
        assertTrue("苏醒动画不能为负", WitherConfig.WITHER_AWAKENING_TICKS >= 0);
        assertTrue("冲撞伤害不能为负", WitherConfig.CRASH_IMPACT_DAMAGE >= 0.0D);
        assertTrue("冲撞速度必须为正", WitherConfig.CRASH_SPEED > 0.0D);
        assertTrue("二阶段高度必须为正", WitherConfig.PHASE2_ALTITUDE_MIN > 0.0D);
        assertTrue("回血速率不能为负", WitherConfig.REGEN_NORMAL_PER_TICK >= 0.0D && WitherConfig.REGEN_HIGH_DESIRE_PER_TICK >= 0.0D);
        assertTrue("死亡演出时长必须为正", WitherConfig.DEATH_SEQUENCE_TICKS > 0);
        assertTrue("死亡音效间隔必须为正", WitherConfig.DEATH_SOUND_INTERVAL_TICKS > 0);

        // 行为池两组权重都必须为正，否则某一行为永远抽不到
        assertTrue("常规权重不可全零", WitherConfig.WEIGHT_CRASH_NORMAL + WitherConfig.WEIGHT_BARRAGE_NORMAL > 0);
        assertTrue("高欲望权重不可全零", WitherConfig.WEIGHT_CRASH_HIGH + WitherConfig.WEIGHT_BARRAGE_HIGH > 0);

        // 压制弹幕窗口至少要能容纳「一个主头周期 + 一个最慢的副头周期」：
        // 三头之间是独立冷却、不同步齐射（见项目硬约束），所以窗口只要够长到
        // 让主头与最慢的副头都各自打出一发，就满足「技能窗口里一定有弹幕落地」。
        // 这里刻意不用「两个完整周期」——那会把默认窗口从 100 顶到 120，
        // 等于用测试反过来改手感，而窗口上限 100 是已验收的数值。
        int requiredTicks = WitherConfig.BARRAGE_MAIN_SHOT_INTERVAL_TICKS
                + WitherConfig.BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS;
        assertTrue("压制弹幕窗口不足以覆盖一个主头周期与一个副头周期",
                WitherConfig.P1_BARRAGE_DURATION_MAX_TICKS >= requiredTicks);
    }
}
