/*
 * 【文件职责】凋零之首（WitherSkull）的发射器：单头独立发射 + BE 式「3 黑 1 蓝」规律弹幕，
 *             并用旁挂表记录每颗弹幕的伤害与颜色标记，供命中回调读取。
 * 【所属模块】wither 核心。
 * 【协作关系】由 WitherBrain.fireReadyHeads() 按三颗头各自的冷却决定本帧谁开火，再调
 *             fireFromHead()；WitherBossHandler 在弹幕命中或消失的回调里调
 *             damageOf / isOurs / isBlue / forget 读取与清理标记。
 * 【关键约束】不新增自定义实体、也不给原版 WitherSkull 加字段 —— 伤害与颜色挂在本类的
 *             WeakHashMap 旁挂表上；"哪颗头、什么节奏"由调用方的冷却决定，本类只负责"发一发"。
 */
package com.fusionwither.wither;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 弹幕发射器：原版 Java 凋零式「一次只从一颗头发射」+ BE 式「3 黑 1 蓝」规律弹幕。
 *
 * <p><b>为什么改成「一次只发一颗头」：</b>早先的实现是三颗头在<b>同一 tick 齐射</b>。
 * 玩家实测反馈「残血追击变成三个头一起发射」，与原版观感不符 ——
 * 原版 Java 凋零是三颗头各持一个独立冷却、各瞄各的。因此本类只提供
 * {@link #fireFromHead} 这个「单头」单元操作，「谁在什么时候开火」完全交给调用方
 * （{@code WitherBrain.fireReadyHeads}）按每颗头的冷却决定，三颗头因此天然错峰。</p>
 *
 * <p><b>为什么用旁挂表而不是自定义实体：</b>弹幕伤害与颜色标记存在
 * {@link #SKULL_DAMAGE} / {@link #BLUE_SKULLS} 里，命中时由 {@code WitherBossHandler} 读取。
 * 这样避免了给原版 {@link WitherSkull} 加字段或覆写它的类 ——
 * 那需要 mixin 改原版实体，侵入性强且容易与其它模组冲突。
 * 两张表都用 {@code WeakHashMap}：弹幕实体被回收后条目自动失效，不会无限增长。</p>
 *
 * <p><b>生命周期：</b>本类是纯静态工具类（私有构造、无实例）；表项在
 * {@link #spawnSkull} 生成时写入，在命中或消失时由 {@link #forget} /
 * {@link #discardAllMarkers} 清理。</p>
 */
public final class SkullVolley {

    /** 弹幕 → 伤害 的旁挂表：命中时用来取"本模组给这颗弹幕设定的伤害"。
     *  <p>用 {@code WeakHashMap} 让弹幕实体被回收后表项自动消失；
     *  外层套 {@code synchronizedMap} 给写入（生成时）与读取（命中回调）之间做并发保护。</p>*/
    private static final Map<WitherSkull, Float> SKULL_DAMAGE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 蓝弹幕标记集合：命中时由 {@code WitherBossHandler} 读取，命中后附加凋零效果。 */
    private static final Set<WitherSkull> BLUE_SKULLS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** 原版凋零开火音效的 {@code levelEvent} 事件 ID。
     *  <p>取自 1.20.1 原版硬编码值（{@code WitherBoss.m_31448_} 内 {@code sipush 1024}），
     *  不是可调参数，因此不进 {@link WitherConfig}。</p>*/
    private static final int SHOOT_SOUND_EVENT = 1024;

    private SkullVolley() {}

    /** 全局弹药循环计数器，实现「3 黑 1 蓝」。
     *  <p>它是静态的、跨所有凋零共享：每发弹幕自增 1，
     *  再对 {@code BARRAGE_SKULLS_PER_CYCLE} 取模得到"本轮第几发"，据此决定颜色，
     *  从而在黑色序列里按固定位置插入一颗蓝弹。用 {@code AtomicInteger} 保证自增的原子性。</p>*/
    private static final java.util.concurrent.atomic.AtomicInteger CYCLE =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 加散布用的随机源。刻意与 {@code WitherBrain} 的决策随机源分开，
     *  避免射击散布与行为池决策互相消耗同一条随机序列、彼此影响随机分布。 */
    private static final Random RNG = new Random();

    /** 主头发射点的垂直偏移（格）。
     *  <p>【原版数值】{@code WitherBoss.m_31516_(0)} 返回 {@code getY() + 3.0}。
     *  它是原版硬编码，不是可调参数，因此不进 {@link WitherConfig}。</p>*/
    private static final double MAIN_HEAD_Y_OFFSET = 3.0D;

    /** 副头发射点的垂直偏移（格）。
     *  <p>【原版数值】{@code m_31516_(n>0)} 返回 {@code getY() + 2.2}。</p>*/
    private static final double SIDE_HEAD_Y_OFFSET = 2.2D;

    /** 副头发射点的水平偏移（格）。【原版数值】{@code m_31514_}/{@code m_31518_} 用 1.3 格。
     *  <p>方向是<b>身体朝向的垂线</b>（左 / 右两侧），不是正前方 —— 原版把两颗副头
     *  放在 {@code yBodyRot + 180 * (head - 1)} 的角度上，而对 yaw 而言
     *  {@code (cos, sin)} 恰好是朝向的垂线方向，加 180° 即取另一侧。</p>*/
    private static final double SIDE_HEAD_H_OFFSET = 1.3D;

    /**
     * 从指定的某一颗头发射一发凋零之首 —— 「三头各自独立射击」的最小单元操作。
     *
     * <p><b>调用时机：</b>由 {@code WitherBrain.fireReadyHeads} 每帧检查三颗头各自的冷却，
     * 对已就绪的那几颗各调一次本方法。因为每颗头的冷却独立，"三个头一起发射"不会发生。</p>
     *
     * <p><b>发射点与瞄准点都照抄原版：</b>发射点见 {@link #headPosition}，
     * 瞄准点是原版 {@code m_31457_} 的 {@code target.getY() + getBbHeight() * 0.5}
     * （目标身体中心），见 {@link #aimDirection}。用原版公式而不是"眼睛位置 + 横向偏移"，
     * 是为了让出膛点与原版凋零一致 —— 玩家看到的头颅确实是从头的位置飞出来的。</p>
     *
     * <p><b>副作用：</b>播一次开火音效（{@link #playShootSound}），生成一枚弹幕并登记
     * 伤害/颜色表项。客户端调用直接返回（本类逻辑是服务端专属）。</p>
     *
     * @param wither  发射者（凋零）
     * @param target  当前锁定目标；为 null 时瞄准方向无法计算，直接不发射
     * @param head    头索引：0 = 主头，1 = 左副头，2 = 右副头
     * @param precise true = Java 式精准（误差取 {@code BARRAGE_AIM_INACCURACY}，接近 0）；
     *                false = BE 式规律（误差取 {@code BARRAGE_LOOSE_AIM_INACCURACY}，准头略差）
     * @param damage  直击伤害（蓝弹会被 {@code SKULL_BLUE_DAMAGE} 覆盖）
     */
    public static void fireFromHead(WitherBoss wither, LivingEntity target, int head,
                                    boolean precise, double damage) {
        Level level = wither.level();
        if (level.isClientSide()) {
            return;
        }
        Vec3 from = headPosition(wither, head);
        Vec3 baseDir = aimDirection(from, target);
        if (baseDir == null) {
            return;
        }
        playShootSound(wither);
        spawnSkull(level, wither, from, baseDir, precise, damage);
    }

    /**
     * 平A 入口：只由主头（中心）发射一发。
     *
     * <p><b>分工约定（用户定稿）：</b>
     * <ul>
     *   <li>平A（一阶段风筝常态、二阶段空窗期普攻）→ <b>只有主头</b>发射；</li>
     *   <li>弹幕压制 / 狂暴 → 三头各自独立发射（原版 Java 凋零式）。</li>
     * </ul>
     * 因此本方法等价于 {@link #fireFromHead}(wither, target, 0, …)。</p>
     *
     * <p><b>副作用：</b>同 {@link #fireFromHead}（服务端生成弹幕 + 播开火音效）。</p>
     *
     * @param wither  发射者（凋零）
     * @param target  当前锁定目标；为 null 时不发射
     * @param precise true = Java 式精准；false = BE 式规律
     * @param damage  直击伤害
     */
    public static void fire(WitherBoss wither, LivingEntity target, boolean precise, double damage) {
        fireFromHead(wither, target, 0, precise, damage);
    }

    /**
     * 取某颗头的发射点（世界坐标）。
     *
     * <p><b>照抄原版 {@code m_31514_(head)} / {@code m_31516_(head)} / {@code m_31518_(head)}：</b>
     * <ul>
     *   <li>主头（{@code head <= 0}）：身体中心正上方 {@code y + 3.0} 格；</li>
     *   <li>副头：{@code y + 2.2} 格，并沿<b>身体朝向的垂线</b>向两侧各偏 1.3 格。</li>
     * </ul></p>
     *
     * <p><b>单位提醒（最易踩的坑）：</b>{@code wither.yBodyRot} 是<b>角度</b>，
     * 必须先 {@code Math.toRadians} 转成弧度才能喂给 {@code Math.cos / Math.sin}
     * （此处已转换）。漏掉这一步会把 1.3 格的水平偏移算成方向完全错误的量，
     * 副头会射向错误的一侧。</p>
     *
     * @param wither 发射者（凋零）
     * @param head   头索引：0 = 主头，1 = 左副头，2 = 右副头
     * @return 该头的世界坐标发射点
     */
    private static Vec3 headPosition(WitherBoss wither, int head) {
        if (head <= 0) {
            return new Vec3(wither.getX(), wither.getY() + MAIN_HEAD_Y_OFFSET, wither.getZ());
        }
        // 副头：左右各一颗。原版的 yBodyRot + 180*(head-1) 对 yaw 而言
        // 就是"身体朝向的垂线 ± 一侧"，这里等价地写成 side * (cos, sin) * 1.3。
        double side = (head == 1) ? 1.0D : -1.0D;
        float rad = (float) Math.toRadians(wither.yBodyRot);
        return new Vec3(
                wither.getX() + side * Math.cos(rad) * SIDE_HEAD_H_OFFSET,
                wither.getY() + SIDE_HEAD_Y_OFFSET,
                wither.getZ() + side * Math.sin(rad) * SIDE_HEAD_H_OFFSET);
    }

    /** 计算从某个发射点指向目标身体中心的方向；无法瞄准时返回 null。
     *  <p>瞄准点取 {@code target.getY() + getBbHeight() * 0.5}，与原版 {@code m_31457_} 一致 ——
     *  瞄脚底会让弹幕整体偏低，瞄眼睛则对高低不同的目标表现不一致；
     *  取身体中心让"出膛点、瞄准点"两端都与原版同源。</p>
     *  <p>两点几乎重合（长度平方 &lt; 1e-6）时返回 null 而不是零向量 ——
     *  零向量归一化会得到 NaN，直接把弹幕打进非法状态。</p>*/
    private static Vec3 aimDirection(Vec3 from, LivingEntity target) {
        Vec3 aim = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);
        Vec3 d = aim.subtract(from);
        return d.lengthSqr() < 1.0E-6D ? null : d.normalize();
    }

    /**
     * 播放原版凋零的开火音效（{@code levelEvent} 事件 ID {@value #SHOOT_SOUND_EVENT} = 1024）。
     *
     * <p><b>为什么必须自己补这一声：</b>原版音效只写在私有发射方法
     * {@code WitherBoss.m_31448_(IDDDZ)V} 的<b>开头</b>，反汇编确证为
     * {@code if (!isSilent()) { levelEvent(null, 1024, blockPosition(), 0) }}。
     * 本模组为落实「完全压制原版攻击」已在 HEAD 取消 {@code m_31448_}
     * （见 {@code WitherBossAttackMixin}），因此这一声也随原版射击一并消失。
     * 若不补回，我方弹幕就会「看得见、听不见」。</p>
     *
     * <p><b>逐字对齐原版，而不是「差不多就行」：</b>
     * <ul>
     *   <li>位置用 {@code blockPosition()}（SRG {@code m_20183_}），
     *       <b>不是</b> {@code getOnPos()}（{@code m_20097_}）—— 后者带约 0.2 格的 y 偏移，
     *       坐标会与原版差一格以内。音效按事件 ID 选曲、按坐标衰减，
     *       只有落在同一点才是真的「一样的声音」。</li>
     *   <li>{@code isSilent()} 守卫同样保留：静音凋零（如安静刷怪）本就不发声，
     *       补音效不该把它的静音属性意外推翻。</li>
     * </ul></p>
     *
     * <p><b>次数与「一次发射」对齐：</b>每颗头开火各播一次 —— 与原版
     * （{@code m_31448_} 每发都播）一致。因为三颗头现在各自独立冷却，
     * 音效也随之各自错峰；不再存在"同一 tick 三发叠成三倍音量"的问题。</p>
     *
     * <p><b>用 4 参重载 {@code levelEvent(Player, int, BlockPos, int)}：</b>
     * 服务器端由它把音效广播给所有玩家；第一个参数是「被排除的玩家」，
     * 传 {@code (Player) null}（显式转型是为了选中该重载）表示不排除任何人，即全场都能听到。</p>
     *
     * @param wither 发射者（凋零）；被标记为静音时直接跳过
     */
    private static void playShootSound(WitherBoss wither) {
        if (!wither.isSilent()) {
            wither.level().levelEvent((Player) null, SHOOT_SOUND_EVENT, wither.blockPosition(), 0);
        }
    }

    /**
     * 生成一颗凋零之首实体，并登记它的伤害 / 颜色标记。
     *
     * <p><b>颜色循环：</b>读全局 {@link #CYCLE} 取自增序号，对
     * {@code BARRAGE_SKULLS_PER_CYCLE} 取模 +1 得到"本轮第几发"，
     * 命中 {@code BARRAGE_BLUE_AT_INDEX} 的那一发是蓝弹（命中时附加凋零效果）。
     * 用 {@code Math.floorMod} 而非 {@code %}，保证计数器将来即使回绕成负数也仍落在合法区间。</p>
     *
     * <p><b>为什么发射点还要沿视线再前移：</b>头部坐标落在身体模型内部，
     * 直接从那里生成会让弹幕先卡在自己身体里、出膛瞬间就触发自身碰撞。
     * 于是沿 {@code wither.getLookAngle()} 再推 {@code SKULL_SPAWN_OFFSET} 格；
     * 位置与初速用两个独立常量（{@code SKULL_SPAWN_OFFSET} / {@code SKULL_VELOCITY}），
     * 不再像早期那样被同一个魔数绑死（那样无法独立调参）。</p>
     *
     * <p><b>副作用：</b>写 {@link #SKULL_DAMAGE}（蓝弹用 {@code SKULL_BLUE_DAMAGE}，
     * 否则用参数 {@code damage}），蓝弹再写 {@link #BLUE_SKULLS}，最后把实体加入世界。</p>
     *
     * @param level   当前世界（服务端）
     * @param wither  发射者（凋零）
     * @param from    该头的发射点
     * @param aimDir  该头的瞄准方向（加散布前）
     * @param precise 瞄准模式，决定用哪一档误差常量
     * @param damage  直击伤害（蓝弹会覆盖它）
     */
    private static void spawnSkull(Level level, WitherBoss wither, Vec3 from,
                                   Vec3 aimDir, boolean precise, double damage) {
        // 本轮第几发 → 决定黑/蓝
        int idx = Math.floorMod(CYCLE.getAndIncrement(), WitherConfig.BARRAGE_SKULLS_PER_CYCLE) + 1;
        boolean blue = (idx == WitherConfig.BARRAGE_BLUE_AT_INDEX);

        double inaccuracy = precise
                ? WitherConfig.BARRAGE_AIM_INACCURACY
                : WitherConfig.BARRAGE_LOOSE_AIM_INACCURACY;
        Vec3 dir = spread(aimDir, inaccuracy, RNG);

        Vec3 look = wither.getLookAngle();
        Vec3 spawn = from.add(look.scale(WitherConfig.SKULL_SPAWN_OFFSET));

        WitherSkull skull = new WitherSkull(level, wither, dir.x, dir.y, dir.z);
        skull.setPos(spawn.x, spawn.y, spawn.z);
        skull.setDeltaMovement(dir.scale(WitherConfig.SKULL_VELOCITY));

        float dmg = (float) (blue ? WitherConfig.SKULL_BLUE_DAMAGE : damage);
        SKULL_DAMAGE.put(skull, dmg);
        if (blue) {
            BLUE_SKULLS.add(skull);
        }
        level.addFreshEntity(skull);
    }

    /**
     * 给一个方向加随机散布。
     *
     * <p><b>参数单位是弧度、不是角度 —— 这是最易踩的坑：</b>{@code radians} 直接乘进旋转量，
     * 配置项 {@code BARRAGE_AIM_INACCURACY} / {@code BARRAGE_LOOSE_AIM_INACCURACY} 也按弧度给定。
     * 若按"角度"理解去填（想让散布 3° 就写 3），散布会放大约 57 倍
     * （3 弧度 ≈ 172°），弹幕几乎不可能命中。散布是<b>对称</b>的
     * （{@code (rand - 0.5) * 2 * radians}，即 ±radians），不会整体偏向一侧。</p>
     *
     * <p><b>退化保护：</b>当方向与竖直轴接近共线（正上/正下射）时叉积会退化为零向量，
     * 因此先用一个固定 X 轴顶替。注意最终旋转实际走的是 {@code dir.xRot(...)}
     * （绕局部 X 轴），这段 {@code axis} 只作退化检查、并不参与最终方向的计算。</p>
     *
     * @param dir     原方向（应为单位向量）
     * @param radians 散布半角，<b>单位弧度</b>；&lt;= 0 表示不加散布，原样返回
     * @param rnd     随机源
     * @return 加过散布并重新归一化的方向
     */
    private static Vec3 spread(Vec3 dir, double radians, Random rnd) {
        if (radians <= 0.0D) {
            return dir;
        }
        // 散布只绕水平轴做俯仰偏移（xRot），因此不需要再构造一个垂直轴向量。
        double angle = (rnd.nextDouble() - 0.5D) * 2.0D * radians;
        return dir.xRot((float) angle).normalize();
    }

    /** 取这颗弹幕被登记的直接伤害。
     *  <p>返回 {@code null} 表示不是本模组发的弹幕（原版残留或别的模组生成的），
     *  调用方应退回原版伤害结算。</p>*/
    public static Float damageOf(WitherSkull skull) {
        return SKULL_DAMAGE.get(skull);
    }

    /** 这颗弹幕是不是本模组发出的 —— 用来把自定义效果只施加在自己的弹幕上。
     *  <p>判据基于 {@link #SKULL_DAMAGE} 的键：标记一旦被清（{@link #discardAllMarkers}），
     *  在途弹幕就会从"我们的"退回"路人的"。</p>*/
    public static boolean isOurs(WitherSkull skull) {
        return skull != null && SKULL_DAMAGE.containsKey(skull);
    }

    /** 这颗弹幕是不是蓝弹（命中时叠加凋零效果的那一颗）。 */
    public static boolean isBlue(WitherSkull skull) {
        return BLUE_SKULLS.contains(skull);
    }

    /** 丢弃全部弹幕标记。
     *  <p>用于「上升期间不攻击」的语义落实（P3 死配置接线）：标记被清除后，
     *  在途弹幕命中时查不到自定义值，会退回原版伤害结算。
     *  这不是「移除弹幕」，而是让本模组对它们的伤害改写失效。</p>*/
    public static void discardAllMarkers() {
        SKULL_DAMAGE.clear();
        BLUE_SKULLS.clear();
    }

    /** 弹幕消失时清理标记（命中或销毁时由命中回调调用），避免旁挂表残留。 */
    public static void forget(WitherSkull skull) {
        SKULL_DAMAGE.remove(skull);
        BLUE_SKULLS.remove(skull);
    }
}
