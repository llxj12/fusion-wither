/*
 * 【文件职责】集中存放凋灵 Boss 改造所需的全部可调数值，是全模组唯一的参数中心。
 * 【所属模块】wither 核心。
 * 【协作关系】被 WitherBrain 及各行为实现（KiteMove / BeDash / Barrage / Crash ……）引用；
 *   逻辑代码里不出现字面数值，调参只改本文件。
 * 【关键约束】常量名与取值域被多处引用，改动前先确认引用点；带【规格给定】的项有硬约束，
 *   带【我定的】/【实机反馈】的项可按手感自由调节。
 */
package com.fusionwither.wither;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 凋灵 Boss 的全部可调数值（纯常量类，禁止实例化）。
 *
 * <p><b>设计要点：</b>状态机（{@link WitherBrain}）与各行为实现里不写任何字面数值，
 * 一律引用这里的常量。这样改数值不需要碰逻辑代码，也不会改出状态机 bug。
 *
 * <p><b>数值来源：</b>用户规格 + Java 版原版实测值 + 多轮实机反馈；每条都标了
 * 「规格给定 / 我定的 / 实机反馈」，方便区分哪些能随便改、哪些有约束。
 *
 * <p><b>调参手感速查：</b>距离类调大 = 打得更远更"风筝"，调小 = 贴得更近；
 * 时长/间隔类调大 = 节奏更慢更松，调小 = 更密更凶；速度类直接决定观感的快慢。
 *
 * <p><b>运行时可调：</b>下表中标了「可配」的字段不再是编译期常量，而是由
 * {@link #SPEC}（Forge 配置系统）在加载/重载时通过 {@link #bind()} 写回。
 * 对应文件为 {@code config/fusion_wither-common.toml}，改完重进世界（或
 * {@code /reload} 触发配置重载）即可生效，无需重新构建。
 * 未标「可配」的字段属于设计常量（改它们等于改设计），仍然只读。
 */
public final class WitherConfig {

    /** 私有构造：纯常量容器，禁止实例化。 */
    private WitherConfig() {}

    // =====================================================================
    //  一、凋灵本体：决定战斗总长度与阶段分界
    // =====================================================================
    //
    //  这一组控制"整场战斗有多长、什么时候切阶段"：血量决定一阶段能观察多久，
    //  阈值决定一/二阶段各占多少血量比，余量防止回血把血量顶回阈值之上。

    /** 凋灵最大生命值（点）。【用户定】600，按基岩版困难难度取值。
     *  <p>注：这套机制下 600 血战斗会偏长，平衡待实测后再调（用户指示「先做出来再说」）。
     *  调小 = 更快进二阶段，调大 = 一阶段风筝更久。</p>*/
    public static double WITHER_MAX_HEALTH = 600.0;

    /** 凋灵护甲值（原版凋灵自带）。*/
    // 【为什么不给凋灵加数值护甲】
    //
    // 历史：这里曾有一个 WITHER_ARMOR = 20 的常量，通过实体加入世界事件
    // 以修饰符形式加给凋灵。实测后果：
    //   原版凋灵护甲 4 + 20 = 24
    //   MC 1.20.1 每点护甲约 4% 减伤、上限 80%
    //   → 减伤被截到 80%，下界合金剑 8 伤害实际只掉 1.6
    //   → 血条 600 需要砍 375 次
    //   用户反馈"近战无法造成伤害"（实际是"每次只掉一点点、看不出来"）。
    //
    // 结论：护甲留给二阶段的「弹射物免疫」机制去表达（那是护盾，不是护甲），
    // 不叠加数值型减伤。否则玩家会完全失去输出的正反馈。

    /** 阶段分界：凋灵血量比例 ≤ 此值进入第二阶段。【规格给定】
     *  <p>比例值 0.0~1.0（本处 0.50 = 半血）。调大 = 更早进二阶段（一阶段更短），
     *  调小 = 二阶段更难触发。</p>*/
    public static double PHASE2_THRESHOLD = 0.50;

    // =====================================================================
    //  一·续、出生苏醒（原版"凋灵激活"入场动画）
    // =====================================================================
    //
    //  这段演出不是本模组写的，而是原版行为，我们只负责"不要把它压掉"：
    //    · 结构召唤（灵魂沙摆成 T 形 + 顶上放凋灵首级）时，
    //      WitherSkullBlock 会调用 WitherBoss.m_31506_()，
    //      把 invulnerableTicks（SRG f_31422_）设为 220，并把血量设为上限的 1/3；
    //    · 之后 WitherBoss.m_8024_ 的 `invulnerableTicks > 0` 分支跑完整段演出：
    //      血条充能倒计时（1 - t/220）、凋灵周身灵魂粒子、苏醒期间完全免伤，
    //      倒计时归零时在眼部高度炸一发威力 7 的爆炸并广播 levelEvent 1023（吼声）；
    //    · 该分支同时跳过全部瞄准与射击 —— 所以本状态机在窗口内也必须什么都不做
    //      （见 WitherBrain.updateAwakening）。
    //
    //  另：本模组原先在"非二阶段"每 tick 清零 invulnerableTicks（注释写作"压制原版冲锋"），
    //  经 SRG 字节码核对，1.20.1 里该字段只服务这段苏醒，清零点只会把演出掐死，
    //  即"激活动画在测试里看不到"的原因。两个清零点均已删除。

    /** 出生苏醒时长（tick）。【原版硬编码 220】= 11 秒。
     *  <p>只被调试命令 {@code /wboss spawn} 引用：原版只在结构召唤时开启这段演出，
     *  用 {@code create + addFreshEntity} 直接生成的凋灵该字段为 0，
     *  按原版规则本就没有激活动画。本值为 220 即"与原版一致"；调大 = 苏醒更久。</p>*/
    public static int WITHER_AWAKENING_TICKS = 220;

    /** 二阶段回血上限的余量（点）。【实机反馈新增，用户给定数值 3】
     *  <p>进入二阶段后回血最多到「二阶段最大血量 300 − 本值」= <b>297</b>，
     *  即永远不越回 50% 阈值以上。</p>
     *  <p><b>修的问题：</b>打进二阶段时血量≈300，而回血每 tick +0.05~0.10，
     *  下一 tick 就变成 300.05 —— 阶段判据越界，
     *  行为看起来变回一阶段（但护盾还在，因为并没有真的退回）。</p>*/
    public static final double REGEN_PHASE2_MARGIN = 3.0;

    // =====================================================================
    //  【控制权划分】原版负责攻击与头转向，本模组负责走位
    // =====================================================================
    //
    //  用户给出的最终分工（明确要求）：
    //    "我只要原版的三头射击，不要它的走位。"
    //
    //  因此：
    //    · 原版 AI **保持运行** —— 三头齐射与头部转向依赖它，
    //      而且含头部旋转插值（m_31446_ / m_31480_），重写必然更差
    //    · 但每帧清掉原版的**寻路**（Navigation.stop），
    //      使原版选出的路径不会真正推动凋灵
    //    · 位移改由本模组直接控制实体坐标，不再与寻路抢速度向量
    //      （「抢速度」是本项目前几轮失败的根源：
    //         原版导航每帧覆盖我们设置的 deltaMovement）
    //
    //  注意：本组开关只控制对应的那一部分，不互相牵连。
    //  之前废弃的 OURS_TAKES_OVER_MOVEMENT_AND_ATTACK 会连攻击一起接管，已删除。

    /** 是否由本模组接管凋灵的**位移**（原版仍负责射击与头部转向）。*/
    public static final boolean OUR_MOVEMENT_TAKEOVER = true;

    /** 是否由本模组发射弹幕。
     *  <p><b>现在为 true</b> —— 这是对先前理解偏差的修正。
     *
     *  <p><b>演进与最终结论：</b>
     *  <ol>
     *   <li>最初为 true：本模组自己发射，用黑/蓝头颅伤害差异化</li>
     *   <li>用户提出"不能借鉴原版三头齐射的设计吗" →
     *       我误以为"连射击都交给原版"，于是改为 false。
     *       <b>后果：平A 与弹幕压制看起来完全一样</b>（都是原版在射），
     *       本模组实现的"平A 主头单发 / 压制三头齐射"一行都没执行，
     *       玩家视角就是"完全没有这两种攻击的区别"。</li>
     *   <li><b>现在改回 true</b>：射击由本模组控制（这样才能区分两种攻击），
     *       形式照抄原版 Java 凋灵 —— <b>三颗头各持一个独立冷却、各自瞄准玩家</b>
     *       （见 {@code SkullVolley.fireFromHead} 与 {@code WitherBrain.fireReadyHeads}）。
     *       同时由 {@code WitherBossAttackMixin} 压制原版射击，避免双份弹幕。</li>
     *  </ol>
     *
     *  <p>用户要的是"凋灵的行为逻辑以本模组为主，只借用原版的三头射击形式"。
     *  即：**开火时机与弹头数量由我们决定，三头并射的观感来自原版的设计思路。**</p>*/
    public static final boolean OUR_ATTACK_ENABLED = true;

    // =====================================================================
    //  走位参数：阶段 × 状态（只放距离与高度，速度归各行为自己的段）
    // =====================================================================
    //
    //  用户规格：
    //    第一阶段（远程输出为主，飞高一点）
    //      · 游走/平A：保持距离
    //      · 弹幕压制：接近玩家几格
    //      · 玩家半血或濒死：进一步接近，追杀斩杀
    //    第二阶段（免疫弹射物，需要让近战打得到）
    //      · 离地 2~3 格；游走贴着玩家
    //      · 弹幕压制：比游走稍远一两格
    //      · 压制结束恢复游走

    /** 一阶段飞行高度（离地格数）。调大 = 更安全但更难被近战打到。—— "飞得高一点"。*/
    public static final double P1_ALTITUDE = 6.0;

    /** 二阶段游走/平A 时与玩家的期望距离（格）—— 贴着玩家。*/
    public static final double P2_IDLE_DISTANCE = 3.0;
    /** 二阶段弹幕压制时与玩家的期望距离（格）—— 比游走**稍远一两格**。*/
    public static final double P2_BARRAGE_DISTANCE = 5.0;

    /** 上下高度跟随的最大速度（格/tick）。调大 = 高度修正更猛，调小 = 更柔和。*/
    public static final double MOVEMENT_MAX_VERTICAL_SPEED = 0.30;
    // 说明：原「水平位移限幅」常量已删除。
    // 它会把 BE 走位的 0.40~0.50 压到 0.45 以下，使规格里的"冲刺快慢差异"
    // 实际只落在 0.40~0.45。现在水平速度完全由各调用点的区间常量决定：
    // 风筝 0.10~0.20、BE 冲刺 0.50~0.62、追杀 0.20~0.30。

    /** 是否允许阶段回退（回血回到 50% 以上时退回一阶段）。
     *  <p><b>默认 false</b>：规格未定义回退行为，而凋灵自身回血会把血量推回
     *  阈值以上，一旦允许回退就会在 50% 血线上反复触发「进入二阶段」
     *  （每次附带大爆炸）。二阶段设计为不可逆。*/
    public static final boolean ALLOW_PHASE_REVERT = false;

    // =====================================================================
    //  二、玩家血量驱动的攻击欲望（一阶段的软性节奏旋钮）
    // =====================================================================
    //
    //  这组决定"凋灵打得凶不凶"：玩家越残血，凋灵越倾向高攻击
    //  （射击更密、回血更快、走位更贴），是玩家能自己感知并利用的反馈回路。

    /** 玩家血量比例 ≤ 此值 → 高攻击欲望。【规格给定】
     *  <p>比例值 0.0~1.0（本处 0.50）。调大 = 更容易被激怒。</p>*/
    public static final double DESIRE_PLAYER_HP_THRESHOLD = 0.50;

    /** 欲望切换冷却（tick），防抖动。【规格给定】2 秒 = 40 tick。调小 = 反应更快但可能来回抖。*/
    public static final int DESIRE_SWITCH_COOLDOWN_TICKS = 40;

    // =====================================================================
    //  三、第一阶段 · 风筝走位（常态：远距离游走）
    // =====================================================================
    //
    //  设计（用户定稿）：按迟滞带决策 ——
    //    d < 近阈  → 后撤（远离玩家方向 + 横向分量）
    //    d > 远阈  → 靠近（朝玩家方向 + 横向分量）
    //    两阈之间  → 横向漂移（垂直于玩家方向，左右随机）
    //    撞墙：把方向投影到墙面切向沿切向走；切向也被挡则随机换向，
    //          并记录"上次撞墙方向"避免在墙角来回撞。
    //
    //  本段取代了原先的 "BE 式游走—停顿—输出"（停顿段已删除）：
    //  停顿段在新节奏结构里是多余的第三种节奏，与技能前摇难以区分。

    /** 后撤触发距离（格）：比它更近就后退。
     *  <p>【实机反馈调整】12.0/4.0 → 7.0/5.0（拉近到六格左右，理由是太远时
     *  副头锁不到玩家仇恨）。<b>本轮再整体外移 1.5 格 → 6.5</b>：
     *  用户实测后要求"风筝判定再远一到两格"。</p>*/
    public static double P1_KITE_RETREAT_DISTANCE = 6.5;
    /** 前进触发距离（格）：比它更远就前进。
     *  <p>与 {@link #P1_KITE_RETREAT_DISTANCE} 之间的 2 格区间是**迟滞带** ——
     *  凋灵在这个区间内保持不动，避免在临界点上反复进退（那正是
     *  "持续缓慢后撤"的成因之一）。<b>本轮与近阈同步外移 1.5 格 → 8.5，
     *  迟滞带宽度保持不变（仍为 2 格）。</b></p>*/
    public static double P1_KITE_APPROACH_DISTANCE = 8.5;

    /** 风筝走位的**决策间隔**（tick）。【实机反馈修复，用户指定 5t】
     *  <p>每本值 tick 重新读一次距离、决定"后撤 / 前进 / 不动"，
     *  并锁定方向与速度；**决策之间保持不变**。</p>
     *  <p><b>为什么必须这样：</b>早先是每 tick 重新决策 ——
     *  距离在两格之间跨越阈值时方向每帧翻转、速度每帧重新随机，
     *  叠加原版导航的残留推力后表现为<b>持续缓慢后撤</b>与速度抖动。
     *  改为固定周期后，一次决策是一条稳定指令，走位可预测、可读招。</p>*/
    public static final int P1_KITE_DECISION_INTERVAL_TICKS = 5;

    /** 后撤速度下限（格/tick）。*/
    public static final double P1_KITE_RETREAT_SPEED_MIN = 0.15;
    /** 后撤速度上限（格/tick）。*/
    public static final double P1_KITE_RETREAT_SPEED_MAX = 0.20;
    /** 横向漂移速度下限（格/tick）。*/
    public static final double P1_KITE_STRAFE_SPEED_MIN = 0.10;
    /** 横向漂移速度上限（格/tick）。*/
    public static final double P1_KITE_STRAFE_SPEED_MAX = 0.15;
    /** 靠近速度下限（格/tick）。*/
    public static final double P1_KITE_APPROACH_SPEED_MIN = 0.15;
    /** 靠近速度上限（格/tick）。*/
    public static final double P1_KITE_APPROACH_SPEED_MAX = 0.20;

    /** 横向分量占前进/后撤方向的比例（0~1）。
     *  <p>设计里要求"后撤/靠近方向 + 横向分量"，本值控制那个分量的强度，
     *  使直线移动变成略带弧线的退避/接近。</p>*/
    public static final double P1_KITE_LATERAL_MIX = 0.55;

    /** 撞墙时判定"前进方向被挡"的探测距离（格）。*/
    public static final double P1_KITE_WALL_PROBE = 1.2;

    // =====================================================================
    //  四、第一阶段 · 技能1：弹幕压制（三头错峰连射的输出窗口）
    // =====================================================================
    //
    //  CD 从技能**结束**开始计；持续窗口内三头各自按射击节奏持续开火，
    //  窗口时长必须容纳至少两个完整射击周期，否则会被玩家读成"技能触发了却没动静"。

    /** 技能1 CD 下限（tick）—— 从技能**结束**开始计。5.5 秒 = 110 tick。调小 = 弹幕压制更频繁。*/
    public static int P1_BARRAGE_CD_MIN_TICKS = 110;
    /** 技能1 CD 上限（tick）。9 秒 = 180 tick。*/
    public static int P1_BARRAGE_CD_MAX_TICKS = 180;
    /** 技能1 持续下限（tick）。2.5 秒 = 50 tick。
     *  <p>【实机反馈调整】20 → 50。<b>为什么必须拉长：</b>射击改为三头各自
     *  独立冷却后（原版节奏为主头 40t、副头 40~60t），20~40 tick 的窗口里
     *  每颗头顶多打出一发 —— 玩家看到的就是"技能触发了却几乎没动静"。
     *  窗口至少要有两个射击周期，弹幕才成"压制"。</p>
     *  <p>与二阶段的 {@link #BARRAGE_DURATION_MIN_TICKS} 对齐，两阶段的
     *  "弹幕压制"时长一致。</p>*/
    public static int P1_BARRAGE_DURATION_MIN_TICKS = 50;
    /** 技能1 持续上限（tick）。5 秒 = 100 tick。
     *  <p>【实机反馈调整】40 → 100，理由同上。</p>*/
    public static int P1_BARRAGE_DURATION_MAX_TICKS = 100;

    // =====================================================================
    //  五、第一阶段 · 技能2：BE 位移（基岩版式高速直线冲刺）
    // =====================================================================
    //
    //  内容：随机 1~2 次快速直线运动；方向不同；不能向后（远离玩家）；
    //        两次之间无停顿；期间禁止攻击。
    //
    //  这组参数共同决定"冲刺看起来多快、冲多远、覆盖多大扇区" ——
    //  总位移 ≈ 平均速度 × 时长，调任一项都会直接改变观感。

    /** 技能2 CD 下限（tick）—— 从技能**结束**开始计。9 秒 = 180 tick。*/
    public static int P1_BE_DASH_CD_MIN_TICKS = 180;
    /** 技能2 CD 上限（tick）。16 秒 = 320 tick。*/
    public static int P1_BE_DASH_CD_MAX_TICKS = 320;
    /** 技能2 持续（tick）。【实机反馈调整】40 → 52（2.6 秒）。
     *  <p>用户反馈"BE 位移不明显"，要求"多位移一段时间或者加一点速度"。
     *  两项都做了：总位移 ≈ 平均速度 × 时长，原 ≈0.45×40≈18 格，
     *  现 ≈0.56×52≈29 格（约 +60%），肉眼可清楚分辨。</p>*/
    public static final int P1_BE_DASH_DURATION_TICKS = 52;
    /** 技能2 单段冲刺速度下限（格/tick）。【实机反馈调整】0.40 → 0.50。*/
    public static double P1_BE_DASH_SPEED_MIN = 0.50;
    /** 技能2 单段冲刺速度上限（格/tick）。【实机反馈调整】0.50 → 0.62。*/
    public static double P1_BE_DASH_SPEED_MAX = 0.62;
    /** 技能2 冲刺段数下限。*/
    public static int P1_BE_DASH_SEGMENTS_MIN = 1;
    /** 技能2 冲刺段数上限。调大 = 一次技能里冲刺次数更多。*/
    public static int P1_BE_DASH_SEGMENTS_MAX = 2;
    /** 技能2 方向随机范围（度）—— 在"不后退"的允许扇区内均匀取向。
     *  <p>180 = 整个前半圆（正前 ±90°），是本模组取的值。</p>*/
    public static final double P1_BE_DASH_ARC_DEGREES = 180.0;

    // =====================================================================
    //  六、第一阶段 · 平A（空窗期主头单发，伤害低于弹幕压制）
    // =====================================================================
    //
    //  两个技能 CD 都没转好时，主头按本间隔单发，作为可观察、可对比的"基础输出节奏"。

    /** 平A 间隔下限（tick）。2 秒 = 40 tick。*/
    public static final int P1_BASIC_ATTACK_INTERVAL_MIN_TICKS = 40;
    /** 平A 间隔上限（tick）。4 秒 = 80 tick。*/
    public static final int P1_BASIC_ATTACK_INTERVAL_MAX_TICKS = 80;

    // =====================================================================
    //  六·续、三头独立射击节奏（原版 Java 凋灵式）
    // =====================================================================
    //
    //  背景（实机反馈重构）：早先"压制弹幕 / 狂暴"都是**三颗头在同一 tick
    //  齐射**（一个共享的 14 tick 计时器），玩家实测反馈两个问题：
    //    ① 一阶段压制弹幕看不出来 —— 技能窗口只有 20~40 tick，而那个计时器
    //       与平A 共用（平A 间隔 40~80），窗口经常整段落在平A 冷却里，
    //       一帧都不发射，看起来就是"技能没触发"；
    //    ② 残血狂暴时是"三个头一起发射"，与原版观感不符。
    //
    //  原版 Java 凋灵怎么做（SRG 反汇编确证，见 javap 记录）：
    //    · 主头：m_8099_ 注册 RangedAttackGoal(this, 1.0, **40**, 20.0f)，
    //      即固定 40 tick 一发，瞄 target.getY() + getBbHeight()*0.5
    //    · 副头：m_8024_ 内 for(i=1..2)，每颗副头各自持有 attackTimes[i]，
    //      实际开火后重置为 tickCount + **40 + rand(20)**
    //    → 两颗副头各有**自己的**节奏，与主头永不同步。
    //
    //  因此现在：三颗头各持一个独立冷却（见 {@code WitherBrain.headShotCd}），
    //  到点各自开火；进入模式/技能时用随机初相位错峰，避免那一帧又变成齐射。
    //
    //  这一组参数决定"每颗头的开火间隔"，三种模式各一套：压制弹幕用 BARRAGE_*，
    //  狂暴用 RAGE_*；调小 = 火力更密、更有压迫感。

    /** 压制弹幕：主头两发之间的间隔（tick）。【原版】RangedAttackGoal 的 40。*/
    public static int BARRAGE_MAIN_SHOT_INTERVAL_TICKS = 40;
    /** 压制弹幕：副头两发之间的间隔下限（tick）。【原版】40 + rand(20) 的 40。*/
    public static int BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS = 40;
    /** 压制弹幕：副头两发之间的间隔上限（tick）。【原版】40 + rand(20) 的 60。*/
    public static int BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS = 60;

    /** 狂暴：主头两发之间的间隔（tick）。
     *  <p>【实机反馈调整】狂暴期的火力比压制期更密（约为原版节奏的两倍），
     *  体现"暴怒追杀"的压力；但仍保持<b>三颗头各自独立错峰</b>，
     *  不再出现"三个头一起发射"。</p>*/
    public static int RAGE_MAIN_SHOT_INTERVAL_TICKS = 20;
    /** 狂暴：副头两发之间的间隔下限（tick）。*/
    public static int RAGE_SIDE_SHOT_INTERVAL_MIN_TICKS = 30;
    /** 狂暴：副头两发之间的间隔上限（tick）。*/
    public static int RAGE_SIDE_SHOT_INTERVAL_MAX_TICKS = 50;

    // =====================================================================
    //  七、第一阶段 · 狂暴模式（目标玩家 ≤ 50%）
    // =====================================================================
    //
    //  切换后：Java 式追杀（直奔玩家、直线贴脸到 1~2 格）
    //          三头独立射击变常态（各按 RAGE_* 节奏，无 CD 间隔）、停止平A、
    //          屏蔽 BE 走位、身上持续冒血红色粒子。

    /** 追杀时与玩家的目标距离（格）—— 贴脸。调小 = 贴得更死。*/
    public static final double P1_RUSHDOWN_DISTANCE = 2.0;
    /** 追杀移动速度下限（格/tick）。*/
    public static final double P1_RUSHDOWN_SPEED_MIN = 0.20;
    /** 追杀移动速度上限（格/tick）。*/
    public static final double P1_RUSHDOWN_SPEED_MAX = 0.30;
    /** 狂暴模式血红色粒子的每次生成间隔（tick）。越小越浓。*/
    public static final int P1_RAGE_PARTICLE_INTERVAL_TICKS = 4;
    /** 狂暴模式血红色粒子的每次生成数量。*/
    public static final int P1_RAGE_PARTICLE_COUNT = 6;

    // 说明：原先这里还有一段"Java 式贴脸追踪"的独立小段
    // （P1_RUSHDOWN_SPEED_MIN/MAX、P1_RUSHDOWN_INTERVAL_MIN/MAX_TICKS）。
    // 重构后追杀参数已并入上方【狂暴模式】段，避免两处重复定义
    // （本次清理正是因为出现了重复常量）。

    // =====================================================================
    //  八、弹幕头颅（黑/蓝凋灵之首的伤害、效果与飞行参数）
    // =====================================================================
    //
    //  这一组决定"一轮弹幕的构成与每颗头的威力"：发数与蓝头位置、黑/蓝头直击伤害、
    //  蓝头附带的凋零效果，以及弹幕发射点偏移与初速度。
    //  调大伤害/凋灵等级 = 玩家血量压力更大，逼玩家更早进入高压状态。

    /** 每轮弹幕的发数。3 黑 1 蓝 = 4。【规格给定】*/
    public static final int BARRAGE_SKULLS_PER_CYCLE = 4;
    /** 一轮里蓝色头颅出现在第几发（1-based）。【规格给定】3 黑 1 蓝。*/
    public static final int BARRAGE_BLUE_AT_INDEX = 4;

    /** 黑头颅直击伤害（点）。【我定的】原版凋灵头颅是 8。*/
    public static double SKULL_BLACK_DAMAGE = 8.0;
    /** 蓝头颅直击伤害（点）。【我定的】带凋零 II。*/
    public static double SKULL_BLUE_DAMAGE = 12.0;
    /** 蓝头颅附加凋零效果等级（0=凋零 I）。【我定的】*/
    public static final int SKULL_BLUE_WITHER_LEVEL = 1;
    /** 蓝头颅凋零效果持续（tick）。【我定的】10 秒。*/
    public static int SKULL_BLUE_WITHER_DURATION_TICKS = 200;

    /** 空窗期普攻伤害（点，低于弹幕压制）。【我定的】*/
    public static final double IDLE_ATTACK_DAMAGE = 6.0;

    /** 弹幕生成点相对眼睛位置的向前偏移（格）。【我定的】
     *  <p>F-13：原实现把位置写成 {@code eye + dir × 1.2}，其中 dir 是<b>归一化瞄准方向</b>
     *  （单位向量），因此实际只偏移 1.2 格；但速度也用了同样的 1.2 倍。
     *  两者耦合导致「发射点」与「弹速」被同一个魔数绑死，无法独立调参。
     *  现拆为：位置用本项、速度用弹速项。</p>*/
    public static final double SKULL_SPAWN_OFFSET = 0.5;
    /** 弹幕初速度（格/tick）。【我定的】原为魔数 1.2。*/
    public static final double SKULL_VELOCITY = 1.2;

    
    // =====================================================================
    //  九、第二阶段 · 行为池（加权随机调度「弹幕压制」与「冲撞」）
    // =====================================================================
    //
    //  二阶段把凋灵压到离地 2~3 格，让近战够得着；每次行为结束按 CD 与权重抽下一个行为。
    //  玩家血量 > 50% 时两行为五五开；玩家残血后权重偏向冲撞（贴身压迫），
    //  体现"追杀"的性状。

    /** 行为 CD 下限（tick）。【规格给定】1.2 秒 ≈ 24 tick。*/
    public static final int BEHAVIOR_COOLDOWN_MIN_TICKS = 24;
    /** 行为 CD 上限（tick）。【规格给定】3.0 秒 = 60 tick。调小 = 行为更密集。*/
    public static final int BEHAVIOR_COOLDOWN_MAX_TICKS = 60;

    /** 权重：玩家血量 > 50% 时，冲撞的权重。【规格给定】50%。*/
    public static int WEIGHT_CRASH_NORMAL = 50;
    /** 权重：玩家血量 > 50% 时，弹幕的权重。【规格给定】50%。*/
    public static int WEIGHT_BARRAGE_NORMAL = 50;
    /** 权重：玩家血量 ≤ 50% 时，冲撞的权重。【规格给定】70%。*/
    public static int WEIGHT_CRASH_HIGH = 70;
    /** 权重：玩家血量 ≤ 50% 时，弹幕的权重。【规格给定】30%。*/
    public static int WEIGHT_BARRAGE_HIGH = 30;

    // ---- 行为 A：弹幕压制（三头持续错峰射击的输出窗口） ----

    /** 弹幕压制持续下限（tick）。【规格给定】2.5 秒 = 50 tick。*/
    public static final int BARRAGE_DURATION_MIN_TICKS = 50;
    /** 弹幕压制持续上限（tick）。【规格给定】5.0 秒 = 100 tick。*/
    public static final int BARRAGE_DURATION_MAX_TICKS = 100;
    /** 弹幕压制期间瞄准误差（弧度）。精准，接近 0。【我定的】*/
    public static final double BARRAGE_AIM_INACCURACY = 0.02;
    /** 平A（非精准模式）的瞄准误差（弧度）。比压制松散 —— 原为
     *  {@code P1_BARRAGE_INACCURACY}，一阶段重构后改名以免与新体系混淆。*/
    public static final double BARRAGE_LOOSE_AIM_INACCURACY = 0.12;

    // 说明：二阶段"冲撞后原地慢慢游走"不需要独立常量 ——
    // 冲撞结束后由 IDLE_WINDOW_MIN_TICKS 那段空窗期承担，
    // 位移由 WitherBrain.phaseTwoMovement() 按距离与高度规则处理。
    // （原先这里的 P2_POST_CRASH_WANDER_TICKS 从未被引用，已删除。）
    //
    // 注意：走位参数（P1_/P2_ 距离与高度、MOVEMENT_MAX_*）
    // 统一放在文件开头的【控制权划分】段之后，避免两处定义漂移
    // （本次重排正是因为出现了 6 个重复常量）。

    /** 距离修正的强度系数（0~1）。越大越积极地把距离拉回目标值。*/
    public static final double DISTANCE_CORRECTION_STRENGTH = 0.6;

    // 说明：原先这里有三条"三头齐射"专用常量 ——
    //   BARRAGE_SHOT_INTERVAL_TICKS（二阶段压制间隔 14t）、
    //   SKULL_HEAD_SPREAD（三头横向偏移 0.7 格）、
    //   SKULL_HEAD_TURN（左右头各自外偏 3°）。
    // 射击改为"三头各自独立冷却"（原版 Java 凋灵式）后它们全部不再被引用，已删除：
    //   · 开火间隔改由 BARRAGE_MAIN/SIDE_SHOT_INTERVAL_* 与 RAGE_* 决定；
    //   · 发射点改照抄原版几何（主头 y+3.0；副头 y+2.2、身体垂线 ±1.3 格），
    //     见 SkullVolley 内的 HEAD_* 常量，不再需要"横向偏移 + 外偏角"的近似。

    // ---- 行为 B：冲撞（蓄力 → 直线高速突进 → 撞到/到点后爆炸） ----

    /** 冲撞蓄力时长（tick）。【我定的】给玩家反应窗口。
     *  <p>【实机反馈调整】原 10 tick 偏长、且蓄力期间没有任何表现，
     *  玩家感觉"没有动画就冲了"。现缩到 8 并在蓄力期间加粒子与音效。</p>*/
    public static final int CRASH_WINDUP_TICKS = 8;
    /** 冲撞速度（格/tick）。【我定的】
     *  <p>【实机反馈调整】0.70 → 1.10 仍偏慢。注意原版 {@code move()} 每 tick
     *  对水平速度施加摩擦（约 ×0.546），设定值必须明显高于"期望可见速度"。
     *  玩家正常跑速约 0.28 格/tick，本值远高于它才有"撞过来"的压迫感。</p>*/
    public static double CRASH_SPEED = 2.20;
    /** 冲撞对玩家的水平击退强度。【我定的】新增 —— 让"被撞飞"有实感。*/
    public static final double CRASH_KNOCKBACK = 2.5;
    /** 冲撞对玩家的垂直击飞强度。【我定的】*/
    public static final double CRASH_KNOCKBACK_LIFT = 0.6;
    /** 冲撞最大距离（格）。【我定的】防止冲出加载范围。
     *  <p>【实机反馈调整】原 24 格在实战中"刚起步就停"。</p>*/
    public static final double CRASH_MAX_DISTANCE = 36.0;
    /** 冲撞直接伤害（点）。【我定的】*/
    public static double CRASH_IMPACT_DAMAGE = 10.0;
    /** 冲撞爆炸威力。【我定的】4.0 会炸石头。*/
    public static final float CRASH_EXPLOSION_POWER = 3.0f;
    /** 冲撞是否破坏地形（false = 只伤害不破方块）。【规格给定】要破坏。*/
    public static boolean CRASH_BREAK_BLOCKS = true;
    /** 冲撞**途中**沿途破坏的半径（格）。【我定的】
     *  <p>【实机反馈新增】用户要求"冲撞时直接破坏路经上的、凋灵可以破坏的方块"。
     *  只覆盖凋灵身体周围，避免误伤远处建筑；仍受 F-09 保护名单约束。</p>*/
    public static final double CRASH_CHARGE_BREAK_RADIUS = 2.5;
    /** 冲撞直接伤害的生效半径（格）。【我定的】
     *  <p>F-13 附带澄清：本项原先叫 CRASH_EXPLOSION_RADIUS，但代码里从未用于爆炸
     *  （爆炸半径由威力决定），只用于判断目标是否在冲撞落点附近。
     *  本轮改名为 CRASH_IMPACT_RADIUS，使其名实相符。</p>*/
    public static final double CRASH_IMPACT_RADIUS = 3.0;
    /** 位移时的路径检查步长（格）。【我定的】F-11：
     *  {@code tryMove} 原先只检查终点包围盒，不检查路径，
     *  0.7 格/tick 的冲撞可能穿过 1 格厚薄墙。
     *  设 0.5 表示位移超过该值时按该步长采样中间点。0 或负数 = 关闭路径检查。*/
    public static final double MOVE_PATH_CHECK_STEP = 0.5;
    /** 上升速度（格/tick）。【我定的】跳回目标高度时的上升快慢。*/
    public static final double CRASH_RISE_SPEED = 0.40;
    /** 冲撞破坏方块的黑名单（这些方块永不破坏）。【我定的】防炸玩家建筑。
     *  <p>分五组便于维护。F-09：原名单只有 9 项，箱子/床/村民工作站点等均可被摧毁，
     *  已扩充为「不可破坏 / 容器 / 功能方块 / 村民工作站点 / 传送门」等组。
     *  <p>名单之外还有 {@link #CRASH_PROTECTED_PREFIXES} 做类别兜底，
     *  覆盖全部容器类与完整床/门族，避免逐个枚举时遗漏。</p>*/
    public static final String[] CRASH_BLOCK_BLACKLIST = {
            // ---- 不可破坏 / 命令类 ----
            "minecraft:bedrock", "minecraft:barrier", "minecraft:command_block",
            "minecraft:chain_command_block", "minecraft:repeating_command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:light",
            // ---- 容器类 ----
            "minecraft:barrel", "minecraft:ender_chest", "minecraft:hopper",
            "minecraft:dispenser", "minecraft:dropper", "minecraft:brewing_stand",
            "minecraft:decorated_pot", "minecraft:chiseled_bookshelf", "minecraft:lectern",
            // ---- 功能方块 ----
            "minecraft:enchanting_table", "minecraft:anvil", "minecraft:chipped_anvil",
            "minecraft:damaged_anvil", "minecraft:beacon", "minecraft:conduit",
            "minecraft:respawn_anchor", "minecraft:lodestone", "minecraft:bell",
            "minecraft:cauldron", "minecraft:water_cauldron", "minecraft:lava_cauldron",
            "minecraft:powder_snow_cauldron", "minecraft:campfire", "minecraft:soul_campfire",
            "minecraft:jukebox", "minecraft:note_block", "minecraft:spawner",
            "minecraft:end_portal_frame", "minecraft:bookshelf",
            // ---- 村民工作站点 ----
            "minecraft:composter", "minecraft:grindstone", "minecraft:smithing_table",
            "minecraft:stonecutter", "minecraft:loom", "minecraft:cartography_table",
            "minecraft:fletching_table", "minecraft:blast_furnace", "minecraft:smoker",
            // ---- 传送门 ----
            "minecraft:nether_portal", "minecraft:end_portal", "minecraft:end_gateway",
            // ---- 其他高价值 / 不可再生 ----
            "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:reinforced_deepslate",
            "minecraft:budding_amethyst", "minecraft:dragon_egg", "minecraft:sponge"
    };

    /** 类别兜底保护：方块注册名含这些子串时永不被破坏。【我定的】
     *  <p>用子串而非枚举，是为了覆盖「全部容器」与「完整床 / 门族」——
     *  逐个枚举箱子种类、木种、床色共约 60 项，容易漏且维护困难。
     *  匹配时同时检查命名空间与路径，因此同样保护 {@code somemod:xxx_chest} 这类模组方块。</p>*/
    public static final String[] CRASH_PROTECTED_PREFIXES = {
            "chest",        // chest / trapped_chest / ender_chest 及未来变体
            "shulker_box",  // 全部染色变体
            "barrel",
            "bed",          // 全部 16 色床
            "door",         // 全部木种的门
            "trapdoor",
            "fence_gate"
    };

    /** 冲撞后垂直上升高度下限（格）。【规格给定】*/
    public static final double CRASH_RISE_MIN_BLOCKS = 8.0;
    /** 冲撞后垂直上升高度上限（格）。【规格给定】*/
    public static final double CRASH_RISE_MAX_BLOCKS = 12.0;
    /** RISE 段超时倍率：实际耗时超过「按速度估算耗时」的这么多倍就强制结束。
     *  <p>存在的理由：上升途中若上方有天花板（洞窟/下界/室内），凋灵永远到不了
     *  目标高度，RISE 段不会结束、active 永不为 null，行为池会永久停摆。见审查报告 F-03。</p>*/
    public static final double CRASH_RISE_TIMEOUT_FACTOR = 2.0;
    /** RISE 段超时的绝对下限（tick）：估算耗时很短时也至少给这么多 tick。*/
    public static final int CRASH_RISE_TIMEOUT_MIN_TICKS = 60;
    /** 上升期间是否禁止攻击（作为输出窗口）。【规格给定】禁止。*/
    public static final boolean CRASH_RISE_NO_ATTACK = true;

    /** 冲撞软 CD（tick）：距上次冲撞开始至少这么久。【规格给定】3 秒 = 60 tick。*/
    public static final int CRASH_SOFT_COOLDOWN_TICKS = 60;

    // =====================================================================
    //  十、空窗期（两个行为 CD 都没转好时，用平A 维持基础输出）
    // =====================================================================
    //
    //  这一段保证"两个技能之间的空档"也有可观察的输出，避免长时间完全静默。

    /** 空窗期普攻间隔下限（tick）。【规格给定】1.5 秒 = 30 tick。
     *  <p>【实机反馈调整】改为 1 秒一发（20 tick），便于肉眼对比
     *  "平A"与"弹幕压制"两种节奏的差异。</p>*/
    public static final int IDLE_ATTACK_INTERVAL_MIN_TICKS = 20;
    /** 空窗期普攻间隔上限（tick）。【实机反馈调整】同样收敛到 1 秒。*/
    public static final int IDLE_ATTACK_INTERVAL_MAX_TICKS = 20;
    /** 空窗期是否保持护甲。【规格给定】保持。*/
    public static final boolean IDLE_KEEP_ARMOR = true;

    // =====================================================================
    //  十一、进入第二阶段的那一刻（入场爆炸的规模）
    // =====================================================================
    //
    //  以下两项控制"血量跨过 50% 时那一发爆炸有多大"；落地下坠流程见随后的【二阶段入场】段。

    /** 进入二阶段时坠落爆炸威力。【我定的】
     *  <p>爆炸的作用范围由威力决定，原版 {@code Level.explode} 不接受独立半径参数，
     *  因此本项是控制入场爆炸范围的唯一配置。</p>*/
    public static final float PHASE2_ENTRY_EXPLOSION_POWER = 4.0f;
    /** 进入二阶段时坠落爆炸是否破坏地形。*/
    public static final boolean PHASE2_ENTRY_BREAK_BLOCKS = true;

    // ---------------------------------------------------------------------
    //  十一·续、二阶段入场：先竖直砸到地面，再爆炸
    // ---------------------------------------------------------------------
    //
    //  用户要求：「第一阶段到第二阶段过渡，凋灵直接向下坠落触碰到地面后再爆炸」。
    //  改之前的行为是**血量跨过 50% 的那一帧直接发生爆炸**，没有任何下落过程 ——
    //  用户看到的落差感就没了。
    //
    //  【为什么不做成"写 deltaMovement 往下掉"】
    //  原版 {@code WitherBoss.aiStep}（{@code m_8107_}）在整个方法里只有一处写速度
    //  （偏移 214 的 {@code setDeltaMovement}），紧接在偏移 253 的
    //  {@code super.aiStep()} 之前；当"到目标水平距离² &gt; 9"时它把速度整体覆盖成
    //  {@code (dir.x*0.3 - v.x*0.6, 0.0, dir.z*0.3 - v.z*0.6)} ——
    //  <b>y 分量被硬写成 0</b>。这正是本项目"上升行为静默失效"的根因。
    //
    //  因此下落复用本模组已确立的做法：<b>坐标驱动</b>。
    //  具体做法在本模组里几乎是零成本的 —— {@code WitherBrain} 每帧会记录本帧起点
    //  （{@code baseY}），并在 {@code aiStep} 末尾由 {@code enforceNow()} 把凋灵
    //  钉回起点；于是"把 {@code baseY} 每帧下移一格半"就等于"让既有的钉回机制
    //  亲手把它按下去"，既不需要写速度、也不需要新机制。
    //
    //  这一组参数决定"坠落多快、能探多深、停顿多久才炸"。

    /** 是否启用「先坠落触地、再爆炸」的二阶段入场。【我定的，待实测调整】*/
    public static boolean PHASE2_ENTRY_FALL_ENABLED = true;

    /** 坠落速度（格/tick）。【我定的，待实测调整】
     *  <p>1.2 约等于原版下落的观感；调大更像"砸下来"，调小更像"压下来"。</p>*/
    public static double PHASE2_ENTRY_FALL_SPEED = 1.2D;

    /** 向下探测地面的最大距离（格）。【我定的】*/
    public static final double PHASE2_ENTRY_FALL_MAX_DEPTH = 96.0D;

    /** 坠落超时（tick）。【我定的，安全网】
     *  <p>正常情况下用不到 —— 落地判据是"到达探测到的地面高度"。
     *  它的作用是在射线打不到地面的极端场景（虚空、超深洞穴）下
     *  强制收敛，避免凋灵永远卡在"坠落中"的早退分支里。
     *  <p>必须满足 {@code MAX_TICKS * FALL_SPEED > MAX_DEPTH}
     *  （200 × 1.2 = 240 &gt; 96），否则正常情况也会先撞超时。</p>*/
    public static final int PHASE2_ENTRY_FALL_MAX_TICKS = 200;

    /** 触地后到爆炸之间的停顿（tick）。【我定的，待实测调整】
     *  <p>3~5 帧的"砸地停顿"能让"触地"这一瞬间在视觉上读得出来，
     *  否则下落与爆炸会糊成一帧。</p>*/
    public static final int PHASE2_ENTRY_GROUND_PAUSE_TICKS = 4;

    /** 二阶段离地高度下限（格）。低于它近战依然够不着，调大 = 更难被近战打到。*/
    public static double PHASE2_ALTITUDE_MIN = 2.0;
    /** 二阶段离地高度上限（格）。*/
    public static double PHASE2_ALTITUDE_MAX = 3.0;

    /** 空窗期的最短保证时长（tick）。【实机反馈新增】
     *  <p><b>为什么需要它（设计缺陷修正）：</b>平A（空窗期普攻）只在"两个 CD
     *  都未就绪"时触发，而行为 CD 只有 1.2~3.0 秒、冲撞期间弹幕 CD 还在扣减 ——
     *  结果空窗期几乎从不出现，<b>平A 实际上从未被玩家看到</b>。
     *
     *  <p>本值给空窗期一个保底时长，使平A 成为可观察、可对比的行为。</p>*/
    public static final int IDLE_WINDOW_MIN_TICKS = 60;

    /** 召唤凋灵骷髅数量下限。【规格给定】3。*/
    public static final int PHASE2_SKELETON_SPAWN_MIN = 3;
    /** 召唤凋灵骷髅数量上限。【规格给定】4。*/
    public static final int PHASE2_SKELETON_SPAWN_MAX = 4;
    /** 凋灵骷髅同时存在上限。【规格给定】4 只，死了才补。*/
    public static final int PHASE2_SKELETON_MAX_ALIVE = 4;
    /** 补召唤检查间隔（tick）。*/
    public static final int PHASE2_SKELETON_REFILL_INTERVAL_TICKS = 40;

    /** 演出（怒吼/护甲闪光）时长（tick）。【规格给定】1 秒 = 20 tick。
     *  <p>【实机反馈调整】规格要求"1 秒"，但实测玩家感觉"刚进去就动了"。
     *  演出是你要求的"无敌但不攻击"窗口，稍长一点让演出可被看清。</p>*/
    public static final int PHASE2_INTRO_TICKS = 30;
    // 说明（三轮实机反馈后的清理）：以下常量已删除 ——
    //   HURT_INVULNERABILITY_TICKS / HURT_INVULNERABILITY_ENABLED
    //   INTRO_INVULNERABLE_TIME / PHASE2_INTRO_INVULNERABLE
    // 原因：演出无敌整体移除。两次实机反馈"近战无法造成伤害"，
    // 而原版 hurt() 里 invulnerableTime > 10 会丢弃小额重复伤害 ——
    // 既然无法确证该字段的写入无副作用，就不再动它。
    // 演出期间现在只做"不攻击"，不再做"无敌"。详见 WitherBrain.handleIntro 的说明。
    
    // =====================================================================
    //  十二、护甲（只免疫弹射物，近战与爆炸不受影响）
    // =====================================================================
    //
    //  说明（P3 死配置清理）：原有一组 ARMOR_BLOCKS_* 开关，
    //  其中 ARMOR_BLOCKS_EXPLOSIONS / ARMOR_BLOCKS_MELEE 已删除 ——
    //  规格明确要求「爆炸伤害与近战仍然有效」，不存在让它们生效的场景，
    //  留着是与规格矛盾的死配置。ARMOR_BLOCKS_PROJECTILES 保留，
    //  它是护甲本身的开关，有实际引用。

    /** 护甲是否免疫弹射物。【规格给定】是。*/
    public static boolean ARMOR_BLOCKS_PROJECTILES = true;

    // =====================================================================
    //  十三、回血（二阶段持续回血，是"必须近战压制"的推力）
    // =====================================================================
    //
    //  一组两个速率：常规值，以及玩家半血以下时的翻倍值（呼应高攻击欲望）。
    //  调大 = 玩家更难磨死它，逼玩家打得更激进、更快贴上去。

    /** 常规回血速度（HP/tick）。【我定的】1 HP/s。*/
    public static double REGEN_NORMAL_PER_TICK = 0.05;
    /** 玩家半血以下时的回血速度（HP/tick）。【规格给定】翻倍。*/
    public static double REGEN_HIGH_DESIRE_PER_TICK = 0.10;

    // =====================================================================
    //  十四、死亡演出（基岩版式：白光 → 拉伸变宽 → 爆炸死亡）
    // =====================================================================
    //
    //  用户要求：「凋灵血量掉完了后，浑身发白光，然后拉伸变宽，最后爆炸死亡」。
    //  这三段在 Java 版原版里**不存在** —— 原版凋灵血量归零就是普通死亡动画
    //  （尸体变红倒地 + 掉落），没有任何白光或形变。所以整段演出必须自己写。
    //
    //  【为什么不能只取消死亡事件（LivingDeathEvent）—— 这是本实现最关键的一条】
    //  LivingDeathEvent 触发时血量**已经是 0**，此时：
    //    · 下一 tick 原版照样走 tickDeath()，尸体会照常消失；
    //    · 但 die() 没被执行 → **下界之星与经验全部丢失**，
    //      玩家打完 BOSS 拿不到战利品（连击杀归属也是错的）。
    //  同理也不能用 discard() 一步到位 —— 一样跳过 die() 的战利品逻辑。
    //
    //  【本实现走的骨架】
    //    ① 在**伤害**事件（LivingDamageEvent）里把"致死一击"垫住：
    //       setAmount 改成"刚好把血量压到 DEATH_ANIM_KEEP_HEALTH"而不是压到 0，
    //       等于把死亡权从原版手里拿走；
    //    ② 演出期间把血量**钉在 1.0 附近**（绝不能为 0 ——
    //       LivingEntity.isDeadOrDying() 就是 getHealth() <= 0，
    //       一旦归零就会走原版死亡路径，演出被打断），并全程免伤；
    //    ③ 演出结束时清掉发光与无敌，先炸一炮，再用一次**真实致命伤**（hurt）
    //       触发原版 die() —— 掉落、击杀归属、死亡音效全部由原版完成。
    //
    //  用 LivingDamageEvent 而不是 LivingHurtEvent 是有意的：
    //  两者都在 LivingEntity.m_6475_（actuallyHurt）内部触发，但
    //  LivingHurtEvent 早于护甲/吸收减免（拿到的不是最终伤害），
    //  LivingDamageEvent 拿到的才是"真正要从血量里减掉的那个值"，
    //  因此 amount >= getHealth() 就是"这一击会打死它"的精确判据。

    /** 是否启用死亡演出。【我定的，待实测调整】*/
    public static boolean DEATH_SEQUENCE_ENABLED = true;

    /** 死亡演出总时长（tick）。【我定的，待实测调整】
     *  <p>4 秒 = 80 tick。前 35% 只是"浑身发白光"，之后开始拉伸变宽，
     *  最后一 tick 爆炸并走真实死亡。</p>*/
    public static int DEATH_SEQUENCE_TICKS = 80;

    /** 演出期间把血量钉在的值。【我定的，必须 &gt; 0】
     *  <p>它同时是<b>客户端识别演出是否在进行</b>的判据之一
     *  （见 {@code WitherDeathRenderHandler}）：服务端把血量钉在 1.0，
     *  客户端读到的同步血量就恒为 1.0，与"普通战斗中恰好剩 1 血"用
     *  「同时发光」区分开。</p>*/
    public static final float DEATH_ANIM_KEEP_HEALTH = 1.0F;

    /** 白光粒子的生成间隔（tick）。【我定的】越小越浓。*/
    public static final int DEATH_PARTICLE_INTERVAL_TICKS = 2;

    /** 白光粒子每次生成数量的基准值。【我定的】随进度递增（末尾约翻倍）。*/
    public static final int DEATH_PARTICLE_COUNT = 12;

    // ---------------------------------------------------------------------
    //  十四·续、死亡演出的持续性音效（模拟 BE 版）
    // ---------------------------------------------------------------------
    //
    //  用户要求：「在凋灵死亡时的动画演出，添加凋灵死亡的**持续性**音效，
    //  模拟 BE 版凋灵的死亡音效」。
    //
    //  原版 Java 版的凋灵死亡只有 {@code die()} 里放的一次 {@code wither_death}，
    //  是一声短促的收场音，衬托不了 4 秒的演出。
    //
    //  【为什么不做一个"真·循环音源"】
    //  那需要自己写一个客户端 {@code SoundInstance} 去接管播放与生命周期，
    //  成本高，还得额外做客户端隔离（本项目已经为渲染新建了一个 Dist.CLIENT 类，
    //  不想再开一个）。这里改用<b>服务端按间隔重发</b>：单次音效本身有长度，
    //  间隔略小于其长度时听感就是连续的。
    //
    //  【"越鸣越沉"是 BE 版死亡音的关键特征】
    //  {@code pitch} 随演出进度从 {@link #DEATH_SOUND_PITCH_START} 线性降到
    //  {@link #DEATH_SOUND_PITCH_END}：音调一路走低，最后接上爆炸，
    //  正好是"哀嚎着塌下去"的观感。
    //
    //  【刻意不在收尾补放死亡音】
    //  {@code finish()} 走的是真实 {@code hurt()} → 原版 {@code die()}，
    //  那里本来就会放一次原版 {@code wither_death}。把它当作整段音效的落点，
    //  所以这里<b>不</b>再重复放同一个音源，避免与爆炸音糊成一片。
    //
    //  音量设为 0 即等于关闭本功能（因此不再单独做一个 enable 开关）。

    /** 演出中"持续音效"的重发间隔（tick）。【我定的，待实测调整】
     *  <p>略小于原版 {@code wither_ambient} 的音效长度时听感才连续；
     *  调小会更吵、更"糊"，调大则会听出断续。</p>*/
    public static int DEATH_SOUND_INTERVAL_TICKS = 20;

    /** 重发音调起点。【我定的，待实测调整】*/
    public static final float DEATH_SOUND_PITCH_START = 1.0F;

    /** 重发音调终点。【我定的，待实测调整】模拟 BE 版"越鸣越沉"。*/
    public static final float DEATH_SOUND_PITCH_END = 0.5F;

    /** 重发音量。【我定的，待实测调整】0 = 关闭持续性音效。*/
    public static final float DEATH_SOUND_VOLUME = 1.0F;

    /** 起手那一声（原版死亡音）的音量。【我定的，待实测调整】
     *  <p>比后续重发更响一点，把演出的"开场"定住。</p>*/
    public static final float DEATH_SOUND_OPEN_VOLUME = 1.2F;

    /** 起手那一声的音调。【我定的，待实测调整】
     *  <p>取得比原调更低，听感上更"沉"、更不像是普通的死亡收场音。</p>*/
    public static final float DEATH_SOUND_OPEN_PITCH = 0.8F;

    /** 死亡爆炸威力。【我定的】与原版凋灵苏醒结束时那一发同为 7.0。*/
    public static final float DEATH_EXPLOSION_POWER = 7.0F;

    /** 死亡爆炸是否破坏地形。【我定的】是；但仍受 F-09 保护名单约束
     *  （见 {@code CrashEffects.filterDetonation} 与
     *  {@code WitherBossHandler.onExplosionDetonate}，箱子/床/工作站点等不破）。*/
    public static final boolean DEATH_EXPLOSION_BREAK_BLOCKS = true;

    /** 【客户端渲染】横向（X/Z）最大放大倍率 —— "变宽"。【我定的，待实测调整】
     *  <p>1.0 = 不变。这是纯客户端数值，服务端不参与。</p>*/
    public static final float DEATH_ANIM_WIDEN_MAX = 1.80F;

    /** 【客户端渲染】纵向（Y）最大放大倍率 —— "拉伸"（拉高）。【我定的，待实测调整】
     *  <p>1.0 = 不变。</p>*/
    public static final float DEATH_ANIM_STRETCH_MAX = 1.40F;

    /** 【客户端渲染】进度小于此值前只有白光、不形变。【我定的，待实测调整】
     *  <p>纯观感分段 —— 对应用户描述的"先发白光，<b>然后</b>拉伸变宽"。</p>*/
    public static final float DEATH_ANIM_DEFORM_START = 0.35F;

    // =====================================================================
    //  十五、调试
    // =====================================================================
    //
    //  用于验收"行为无法被玩家背板"：把每次选中的行为写进日志。

    /**
     * 是否把每次选中的行为写进日志（用于验收「无法背板」）。
     *
     * <p><b>可配</b>：运行时可在 {@code config/fusion_wither-common.toml} 打开，
     * 用于验收「行为不可预测」，正式发布默认关闭。</p>
     */
    public static boolean DEBUG_LOG_BEHAVIOR_SEQUENCE = false;
    // 说明（P3 死配置清理）：原 DEBUG_SHOW_STATE 已删除 ——
    // 本项目从未实现动作栏/ HUD 显示，规格也未要求，属纯死配置。

    // =====================================================================
    //  运行时可调参数：Forge 配置系统的落地部分
    // =====================================================================
    //
    //  上面标了「可配」的字段都是普通静态字段，初值就是原默认值 ——
    //  因此在配置文件缺失、或配置尚未加载时，行为与「全常量时代」完全一致，
    //  这条设计保证了引入配置不会改变默认手感。
    //  bind() 在配置加载/重载时把 TOML 里的值写回这些字段；
    //  逻辑代码只读 WitherConfig.XXX，不需要知道配置的存在。

    /** 配置规格；由 {@code NewMod} 注册为 COMMON 类型，文件位于 config 目录。 */
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.ConfigValue<Double> V_WITHER_MAX_HEALTH;
    private static final ForgeConfigSpec.ConfigValue<Double> V_PHASE2_THRESHOLD;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_WITHER_AWAKENING_TICKS;

    private static final ForgeConfigSpec.ConfigValue<Double> V_P1_KITE_RETREAT_DISTANCE;
    private static final ForgeConfigSpec.ConfigValue<Double> V_P1_KITE_APPROACH_DISTANCE;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_P1_BARRAGE_CD_MIN_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_P1_BARRAGE_CD_MAX_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_P1_BARRAGE_DURATION_MIN_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_P1_BARRAGE_DURATION_MAX_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_P1_BE_DASH_CD_MIN_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_P1_BE_DASH_CD_MAX_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Double> V_P1_BE_DASH_SPEED_MIN;
    private static final ForgeConfigSpec.ConfigValue<Double> V_P1_BE_DASH_SPEED_MAX;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_P1_BE_DASH_SEGMENTS_MIN;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_P1_BE_DASH_SEGMENTS_MAX;

    private static final ForgeConfigSpec.ConfigValue<Double> V_SKULL_BLACK_DAMAGE;
    private static final ForgeConfigSpec.ConfigValue<Double> V_SKULL_BLUE_DAMAGE;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_SKULL_BLUE_WITHER_DURATION_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_BARRAGE_MAIN_SHOT_INTERVAL_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_RAGE_MAIN_SHOT_INTERVAL_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_RAGE_SIDE_SHOT_INTERVAL_MIN_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_RAGE_SIDE_SHOT_INTERVAL_MAX_TICKS;

    private static final ForgeConfigSpec.ConfigValue<Double> V_PHASE2_ALTITUDE_MIN;
    private static final ForgeConfigSpec.ConfigValue<Double> V_PHASE2_ALTITUDE_MAX;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_WEIGHT_CRASH_NORMAL;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_WEIGHT_BARRAGE_NORMAL;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_WEIGHT_CRASH_HIGH;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_WEIGHT_BARRAGE_HIGH;
    private static final ForgeConfigSpec.ConfigValue<Double> V_CRASH_IMPACT_DAMAGE;
    private static final ForgeConfigSpec.ConfigValue<Double> V_CRASH_SPEED;
    private static final ForgeConfigSpec.ConfigValue<Boolean> V_CRASH_BREAK_BLOCKS;
    private static final ForgeConfigSpec.ConfigValue<Boolean> V_ARMOR_BLOCKS_PROJECTILES;
    private static final ForgeConfigSpec.ConfigValue<Boolean> V_PHASE2_ENTRY_FALL_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<Double> V_PHASE2_ENTRY_FALL_SPEED;

    private static final ForgeConfigSpec.ConfigValue<Double> V_REGEN_NORMAL_PER_TICK;
    private static final ForgeConfigSpec.ConfigValue<Double> V_REGEN_HIGH_DESIRE_PER_TICK;

    private static final ForgeConfigSpec.ConfigValue<Boolean> V_DEATH_SEQUENCE_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_DEATH_SEQUENCE_TICKS;
    private static final ForgeConfigSpec.ConfigValue<Integer> V_DEATH_SOUND_INTERVAL_TICKS;

    private static final ForgeConfigSpec.ConfigValue<Boolean> V_DEBUG_LOG_BEHAVIOR_SEQUENCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        // ---- 本体 / 阶段 ----
        b.comment("凋灵本体与阶段分界。血量决定整场战斗长度，阈值决定两个阶段的血量占比。")
                .push("wither");
        V_WITHER_MAX_HEALTH = b.comment("最大生命值（点）。调小更快进二阶段。")
                .defineInRange("maxHealth", 600.0D, 1.0D, 5000.0D);
        V_PHASE2_THRESHOLD = b.comment("进入二阶段的血量比例（0~1）。")
                .defineInRange("phase2Threshold", 0.50D, 0.05D, 0.95D);
        V_WITHER_AWAKENING_TICKS = b.comment("出生苏醒演出时长（tick）。原版为 220（约 11 秒）。")
                .defineInRange("awakeningTicks", 220, 0, 2000);
        b.pop();

        // ---- 一阶段：风筝与技能节奏 ----
        b.comment("一阶段风筝距离、双技能冷却与窗口、BE 位移节奏。")
                .push("phase1");
        V_P1_KITE_RETREAT_DISTANCE = b.comment("过近时后撤的目标距离（格）。")
                .defineInRange("kiteRetreatDistance", 6.5D, 0.0D, 64.0D);
        V_P1_KITE_APPROACH_DISTANCE = b.comment("过远时接近的目标距离（格）。")
                .defineInRange("kiteApproachDistance", 8.5D, 0.0D, 64.0D);
        V_P1_BARRAGE_CD_MIN_TICKS = b.comment("压制弹幕冷却下限（tick）。")
                .defineInRange("barrageCdMinTicks", 110, 0, 5000);
        V_P1_BARRAGE_CD_MAX_TICKS = b.comment("压制弹幕冷却上限（tick）。")
                .defineInRange("barrageCdMaxTicks", 180, 0, 5000);
        V_P1_BARRAGE_DURATION_MIN_TICKS = b.comment("压制弹幕窗口下限（tick）。须容纳至少两个射击周期。")
                .defineInRange("barrageDurationMinTicks", 50, 1, 1000);
        V_P1_BARRAGE_DURATION_MAX_TICKS = b.comment("压制弹幕窗口上限（tick）。")
                .defineInRange("barrageDurationMaxTicks", 100, 1, 1000);
        V_P1_BE_DASH_CD_MIN_TICKS = b.comment("BE 位移冷却下限（tick）。")
                .defineInRange("beDashCdMinTicks", 180, 0, 5000);
        V_P1_BE_DASH_CD_MAX_TICKS = b.comment("BE 位移冷却上限（tick）。")
                .defineInRange("beDashCdMaxTicks", 320, 0, 5000);
        V_P1_BE_DASH_SPEED_MIN = b.comment("BE 位移速度下限（格/tick）。")
                .defineInRange("beDashSpeedMin", 0.50D, 0.0D, 5.0D);
        V_P1_BE_DASH_SPEED_MAX = b.comment("BE 位移速度上限（格/tick）。")
                .defineInRange("beDashSpeedMax", 0.62D, 0.0D, 5.0D);
        V_P1_BE_DASH_SEGMENTS_MIN = b.comment("单次 BE 位移段数下限。")
                .defineInRange("beDashSegmentsMin", 1, 1, 5);
        V_P1_BE_DASH_SEGMENTS_MAX = b.comment("单次 BE 位移段数上限。")
                .defineInRange("beDashSegmentsMax", 2, 1, 5);
        b.pop();

        // ---- 射击：三头独立冷却与弹幕伤害 ----
        b.comment("凋灵之首伤害与三头错峰射击间隔。调小 = 火力更密。")
                .push("shots");
        V_SKULL_BLACK_DAMAGE = b.comment("黑色凋灵之首直击伤害（点）。")
                .defineInRange("blackSkullDamage", 8.0D, 0.0D, 100.0D);
        V_SKULL_BLUE_DAMAGE = b.comment("蓝色凋灵之首直击伤害（点）。")
                .defineInRange("blueSkullDamage", 12.0D, 0.0D, 100.0D);
        V_SKULL_BLUE_WITHER_DURATION_TICKS = b.comment("蓝色弹附加的凋零效果时长（tick）。")
                .defineInRange("blueWitherDurationTicks", 200, 0, 10000);
        V_BARRAGE_MAIN_SHOT_INTERVAL_TICKS = b.comment("压制弹幕期主头射击间隔（tick）。")
                .defineInRange("barrageMainIntervalTicks", 40, 1, 2000);
        V_BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS = b.comment("压制弹幕期副头射击间隔下限（tick）。")
                .defineInRange("barrageSideIntervalMinTicks", 40, 1, 2000);
        V_BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS = b.comment("压制弹幕期副头射击间隔上限（tick）。")
                .defineInRange("barrageSideIntervalMaxTicks", 60, 1, 2000);
        V_RAGE_MAIN_SHOT_INTERVAL_TICKS = b.comment("狂暴模式主头射击间隔（tick）。")
                .defineInRange("rageMainIntervalTicks", 20, 1, 2000);
        V_RAGE_SIDE_SHOT_INTERVAL_MIN_TICKS = b.comment("狂暴模式副头射击间隔下限（tick）。")
                .defineInRange("rageSideIntervalMinTicks", 30, 1, 2000);
        V_RAGE_SIDE_SHOT_INTERVAL_MAX_TICKS = b.comment("狂暴模式副头射击间隔上限（tick）。")
                .defineInRange("rageSideIntervalMaxTicks", 50, 1, 2000);
        b.pop();

        // ---- 二阶段：压制高度、行为池权重、冲撞 ----
        b.comment("二阶段压制高度、行为池权重与冲撞参数。权重为相对值，同组之和不必等于 100。")
                .push("phase2");
        V_PHASE2_ALTITUDE_MIN = b.comment("压制高度下限（格，离地）。近战需够得着。")
                .defineInRange("altitudeMin", 2.0D, 0.5D, 16.0D);
        V_PHASE2_ALTITUDE_MAX = b.comment("压制高度上限（格，离地）。")
                .defineInRange("altitudeMax", 3.0D, 0.5D, 16.0D);
        V_WEIGHT_CRASH_NORMAL = b.comment("常规欲望下的冲撞权重。")
                .defineInRange("crashWeightNormal", 50, 0, 1000);
        V_WEIGHT_BARRAGE_NORMAL = b.comment("常规欲望下的压制弹幕权重。")
                .defineInRange("barrageWeightNormal", 50, 0, 1000);
        V_WEIGHT_CRASH_HIGH = b.comment("高攻击欲望下的冲撞权重。")
                .defineInRange("crashWeightHigh", 70, 0, 1000);
        V_WEIGHT_BARRAGE_HIGH = b.comment("高攻击欲望下的压制弹幕权重。")
                .defineInRange("barrageWeightHigh", 30, 0, 1000);
        V_CRASH_IMPACT_DAMAGE = b.comment("冲撞命中伤害（点）。")
                .defineInRange("crashImpactDamage", 10.0D, 0.0D, 100.0D);
        V_CRASH_SPEED = b.comment("冲撞水平速度（格/tick）。")
                .defineInRange("crashSpeed", 2.20D, 0.1D, 5.0D);
        V_CRASH_BREAK_BLOCKS = b.comment("冲撞是否破坏方块（黑名单内的方块始终保护）。")
                .define("crashBreakBlocks", true);
        V_ARMOR_BLOCKS_PROJECTILES = b.comment("二阶段是否反弹一切投射物。")
                .define("armorBlocksProjectiles", true);
        V_PHASE2_ENTRY_FALL_ENABLED = b.comment("二阶段入场是否以下坠触地开场。")
                .define("phase2EntryFallEnabled", true);
        V_PHASE2_ENTRY_FALL_SPEED = b.comment("入场下坠速度（格/tick）。")
                .defineInRange("phase2EntryFallSpeed", 1.2D, 0.1D, 5.0D);
        b.pop();

        // ---- 回血与演出 ----
        b.comment("回血速率与死亡演出节奏。")
                .push("misc");
        V_REGEN_NORMAL_PER_TICK = b.comment("常规欲望下每 tick 回血（点）。")
                .defineInRange("regenNormalPerTick", 0.05D, 0.0D, 10.0D);
        V_REGEN_HIGH_DESIRE_PER_TICK = b.comment("高攻击欲望下每 tick 回血（点）。")
                .defineInRange("regenHighDesirePerTick", 0.10D, 0.0D, 10.0D);
        V_DEATH_SEQUENCE_ENABLED = b.comment("是否启用自定义死亡演出。")
                .define("deathSequenceEnabled", true);
        V_DEATH_SEQUENCE_TICKS = b.comment("死亡演出总时长（tick）。")
                .defineInRange("deathSequenceTicks", 80, 1, 2000);
        V_DEATH_SOUND_INTERVAL_TICKS = b.comment("死亡演出期间持续哀嚎的重发间隔（tick）。")
                .defineInRange("deathSoundIntervalTicks", 20, 1, 200);
        V_DEBUG_LOG_BEHAVIOR_SEQUENCE = b.comment("把每次选中的行为写进日志（验收用，正式游玩请关闭）。")
                .define("debugLogBehaviorSequence", false);
        b.pop();

        SPEC = b.build();
    }

    /**
     * 把配置文件里的值写回上方那些「可配」字段。
     *
     * <p>由 {@code NewMod} 的配置事件在加载/重载时调用。配置尚未加载时不会调用，
     * 此时字段保持各自声明的默认值，因此默认行为等价于「全常量」版本。</p>
     */
    public static void bind() {
        WITHER_MAX_HEALTH = V_WITHER_MAX_HEALTH.get();
        PHASE2_THRESHOLD = V_PHASE2_THRESHOLD.get();
        WITHER_AWAKENING_TICKS = V_WITHER_AWAKENING_TICKS.get();

        P1_KITE_RETREAT_DISTANCE = V_P1_KITE_RETREAT_DISTANCE.get();
        P1_KITE_APPROACH_DISTANCE = V_P1_KITE_APPROACH_DISTANCE.get();
        P1_BARRAGE_CD_MIN_TICKS = V_P1_BARRAGE_CD_MIN_TICKS.get();
        P1_BARRAGE_CD_MAX_TICKS = V_P1_BARRAGE_CD_MAX_TICKS.get();
        P1_BARRAGE_DURATION_MIN_TICKS = V_P1_BARRAGE_DURATION_MIN_TICKS.get();
        P1_BARRAGE_DURATION_MAX_TICKS = V_P1_BARRAGE_DURATION_MAX_TICKS.get();
        P1_BE_DASH_CD_MIN_TICKS = V_P1_BE_DASH_CD_MIN_TICKS.get();
        P1_BE_DASH_CD_MAX_TICKS = V_P1_BE_DASH_CD_MAX_TICKS.get();
        P1_BE_DASH_SPEED_MIN = V_P1_BE_DASH_SPEED_MIN.get();
        P1_BE_DASH_SPEED_MAX = V_P1_BE_DASH_SPEED_MAX.get();
        P1_BE_DASH_SEGMENTS_MIN = V_P1_BE_DASH_SEGMENTS_MIN.get();
        P1_BE_DASH_SEGMENTS_MAX = V_P1_BE_DASH_SEGMENTS_MAX.get();

        SKULL_BLACK_DAMAGE = V_SKULL_BLACK_DAMAGE.get();
        SKULL_BLUE_DAMAGE = V_SKULL_BLUE_DAMAGE.get();
        SKULL_BLUE_WITHER_DURATION_TICKS = V_SKULL_BLUE_WITHER_DURATION_TICKS.get();
        BARRAGE_MAIN_SHOT_INTERVAL_TICKS = V_BARRAGE_MAIN_SHOT_INTERVAL_TICKS.get();
        BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS = V_BARRAGE_SIDE_SHOT_INTERVAL_MIN_TICKS.get();
        BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS = V_BARRAGE_SIDE_SHOT_INTERVAL_MAX_TICKS.get();
        RAGE_MAIN_SHOT_INTERVAL_TICKS = V_RAGE_MAIN_SHOT_INTERVAL_TICKS.get();
        RAGE_SIDE_SHOT_INTERVAL_MIN_TICKS = V_RAGE_SIDE_SHOT_INTERVAL_MIN_TICKS.get();
        RAGE_SIDE_SHOT_INTERVAL_MAX_TICKS = V_RAGE_SIDE_SHOT_INTERVAL_MAX_TICKS.get();

        PHASE2_ALTITUDE_MIN = V_PHASE2_ALTITUDE_MIN.get();
        PHASE2_ALTITUDE_MAX = V_PHASE2_ALTITUDE_MAX.get();
        WEIGHT_CRASH_NORMAL = V_WEIGHT_CRASH_NORMAL.get();
        WEIGHT_BARRAGE_NORMAL = V_WEIGHT_BARRAGE_NORMAL.get();
        WEIGHT_CRASH_HIGH = V_WEIGHT_CRASH_HIGH.get();
        WEIGHT_BARRAGE_HIGH = V_WEIGHT_BARRAGE_HIGH.get();
        CRASH_IMPACT_DAMAGE = V_CRASH_IMPACT_DAMAGE.get();
        CRASH_SPEED = V_CRASH_SPEED.get();
        CRASH_BREAK_BLOCKS = V_CRASH_BREAK_BLOCKS.get();
        ARMOR_BLOCKS_PROJECTILES = V_ARMOR_BLOCKS_PROJECTILES.get();
        PHASE2_ENTRY_FALL_ENABLED = V_PHASE2_ENTRY_FALL_ENABLED.get();
        PHASE2_ENTRY_FALL_SPEED = V_PHASE2_ENTRY_FALL_SPEED.get();

        REGEN_NORMAL_PER_TICK = V_REGEN_NORMAL_PER_TICK.get();
        REGEN_HIGH_DESIRE_PER_TICK = V_REGEN_HIGH_DESIRE_PER_TICK.get();

        DEATH_SEQUENCE_ENABLED = V_DEATH_SEQUENCE_ENABLED.get();
        DEATH_SEQUENCE_TICKS = V_DEATH_SEQUENCE_TICKS.get();
        DEATH_SOUND_INTERVAL_TICKS = V_DEATH_SOUND_INTERVAL_TICKS.get();

        DEBUG_LOG_BEHAVIOR_SEQUENCE = V_DEBUG_LOG_BEHAVIOR_SEQUENCE.get();
    }
}
