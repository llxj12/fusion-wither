/*
 * 【文件职责】模组入口类：注册本模组自有的物品与创造页，并在构造器里把凋零 Boss 的运行时事件装配上总线。
 * 【所属模块】模组入口（com.fusionwither 包根）。
 * 【协作关系】@Mod 注解让 Forge 在加载期实例化本类；构造器先取得 MOD 总线把 ITEMS / CREATIVE_MODE_TABS
 *   两个 DeferredRegister 挂上去，再调用 ModSetup.registerGameBusEvents() 把 WitherBossHandler 的运行时回调
 *   挂到游戏总线。启动自检打印的数值全部读自 wither.WitherConfig，保证配置只有一个来源。
 * 【关键约束】MODID 必须与 gradle.properties 的 mod_id 一致，否则 Forge 会报 modid 不匹配；
 *   本类只做「注册 + 启动日志」，不含任何战斗逻辑，所以调战斗问题不需要看这里。
 */
package com.fusionwither;

import com.mojang.logging.LogUtils;
import com.fusionwither.wither.WitherBossHandler;
import com.fusionwither.wither.WitherConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

/**
 * 模组主类，同时是 Forge 声明的入口点。
 *
 * <p><b>为什么注册器字段是 {@code public static final}：</b>{@link DeferredRegister} 必须在类加载期
 * 就建好并绑定 MODID 命名空间，其他类（将来的物品 / 方块类）通过 {@link #ITEMS} 等字段追加条目，
 * 从而保证整个模组的注册对象共用同一个命名空间与同一个注册时机（注册表冻结之前）。
 *
 * <p><b>两条总线的分工：</b>MOD 总线只承载注册类事件（本类里的两个 {@code register(modBus)}），
 * 游戏运行期的事件（实体 tick / 伤害 / 弹射物命中 …）属于游戏总线，统一由 {@link ModSetup#registerGameBusEvents()}
 * 转发到 {@code WitherBossHandler}，避免入口类与战斗逻辑互相耦合。
 *
 * <p><b>生命周期：</b>由 Forge 在模组加载阶段创建唯一实例，构造器执行完即完成全部注册；
 * 本类不再参与运行期逻辑。
 */
@Mod(NewMod.MODID)
public class NewMod {

    /** 模组命名空间。改身份时这里与 {@code gradle.properties} 的 {@code mod_id} 必须同步修改。 */
    public static final String MODID = "fusion_wither";

    /** 模组日志器。全模组统一用它打印，便于按日志前缀过滤凋零相关信息。 */
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 物品注册器，绑定到本模组命名空间 {@link #MODID}。
     * <p>当前尚无自制物品，字段先建好是为了让后续物品类有统一的注册入口。
     */
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    /** 创造模式标签页注册器，同样是先建好的统一入口，模式与 {@link #ITEMS} 一致。 */
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    /**
     * 模组专属创造模式标签页，排在原版「战斗」页之后（凋零相关内容与战斗分类相邻，方便查找）。
     *
     * <p><b>为什么用原版下界之星当图标：</b>只是为了满足 {@code CreativeModeTab.builder()} 的必填项，
     * 让标签页能建出来；等本模组有正式物品后，把这个占位图标换成该物品，并在
     * {@code displayItems} 回调里用 {@code output.accept(...)} 逐个加入条目。
     */
    public static final RegistryObject<CreativeModeTab> TAB = CREATIVE_MODE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .title(Component.translatable("itemGroup." + MODID))
                    .icon(() -> net.minecraft.world.item.Items.NETHER_STAR.getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // 本模组暂无自制物品，正式物品就绪后用 output.accept(...) 加到这里
                    })
                    .build());

    /**
     * 构造器 —— Forge 在模组加载期调用，本类的全部副作用都在这里发生。
     *
     * <p><b>为什么注册走 {@code context.getModEventBus()} 而不是 {@code MinecraftForge.EVENT_BUS}：</b>
     * {@link DeferredRegister#register(IEventBus)} 注册的是注册表填充事件，属于模组生命周期事件，
     * 只有 MOD 总线才会在正确的时机触发它们；挂错总线会导致注册项缺失。
     *
     * <p><b>为什么运行时事件交给 {@link ModSetup}：</b>凋零的行为状态机、伤害改写、弹射物拦截等
     * 全部是游戏总线事件，集中由 {@link ModSetup#registerGameBusEvents()} 装配，入口类只喊一句，
     * 保证「入口」与「运行时逻辑」两边互不引用。
     *
     * @param context Forge 注入的模组加载上下文，用于取得 MOD 总线
     */
    public NewMod(FMLJavaModLoadingContext context) {
        IEventBus modBus = context.getModEventBus();
        ITEMS.register(modBus);
        CREATIVE_MODE_TABS.register(modBus);

        // 可调参数的配置文件：COMMON 类型在客户端与服务端都会生成，
        // 与凋零的判定/演出逻辑同时存在于两侧，因此不能用 CLIENT/SERVER 类型。
        // 用注入的 context 注册，而不是已标记待删除的 ModLoadingContext.get()。
        context.registerConfig(ModConfig.Type.COMMON, WitherConfig.SPEC);
        // 配置在加载与重载时都会触发，两个时机都要把值写回 WitherConfig。
        modBus.addListener(NewMod::onConfigLoad);

        // 凋零的运行时事件挂游戏总线（MOD 总线只放注册与生命周期类事件）
        ModSetup.registerGameBusEvents();

        LOGGER.info("[凋零] 凋灵 Boss 已装载：行为状态机 + 随机行为池");
        logBuildInfo();
        logSelftest();
    }

    /**
     * 配置加载/重载时把 TOML 里的值写回 {@link WitherConfig} 的可调字段。
     *
     * <p><b>为什么挂在 MOD 总线：</b>{@code ModConfigEvent} 是模组生命周期事件，
     * 与配置文件解析同源，挂游戏总线收不到。</p>
     *
     * <p><b>为什么监听 {@code ModConfigEvent} 而不是它的两个子类：</b>父类型同时覆盖
     * {@code Loading}（首次加载）与 {@code Reloading}（{@code /reload} 或改文件后重载），
     * 漏掉任一分支都会出现「改了配置但没生效」。</p>
     *
     * <p><b>副作用：</b>只写 {@code WitherConfig} 的静态字段，不触碰任何存活实体。</p>
     *
     * @param event 配置加载或重载事件（本模组只有一个 COMMON 配置，无需再鉴别文件）
     */
    private static void onConfigLoad(ModConfigEvent event) {
        WitherConfig.bind();
        LOGGER.info("[凋零/配置] 已载入可调参数（{}）", event.getConfig().getFileName());
    }

    /**
     * 打印本 jar 的构建指纹，用于判断「游戏到底加载了哪一版代码」。
     *
     * <p><b>为什么需要这一行：</b>实机调试期间多次出现「改了代码但看不出变化」的情况，
     * 只凭现象无法区分是「代码没生效」还是「游戏加载的还是旧 jar」。指纹文件由构建时自动生成
     * （见 build.gradle 的 generateBuildInfo 任务），源文件一改它就变，因此日志里的这一行
     * 是本模组唯一可靠的版本凭据。
     *
     * <p><b>副作用与容错：</b>只读资源文件并打日志，不修改任何状态；资源缺失（例如 IDE 直接运行、
     * 没有走打包流程）时降级为一条 warn，不影响模组继续加载。
     */
    private static void logBuildInfo() {
        try (var in = NewMod.class.getResourceAsStream("/fusion_wither-build-info.txt")) {
            if (in == null) {
                LOGGER.warn("[凋零/版本] 未找到构建信息（可能是 IDE 直接运行）");
                return;
            }
            var text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            LOGGER.info("[凋零/版本] 构建指纹 = {}", text.replace("\n", "  "));
        } catch (Exception e) {
            LOGGER.warn("[凋零/版本] 读取构建信息失败：{}", e.toString());
        }
    }

    /**
     * 启动自检：把关键战斗数值各打一行到日志，便于「不开游戏、只看日志」核对配置有没有被正确读到。
     *
     * <p><b>为什么读 {@code WitherConfig} 而不是各行为类里的字段：</b>这些数值是全模组的配置单点
     * （阈值、权重、冷却、上升格数都只在那里定义），在这里集中打印才能发现「配置改了但行为没跟着变」。
     *
     * <p><b>副作用：</b>仅打印日志，不写任何字段；数值来自静态常量，因此可在构造器里安全调用。
     */
    private static void logSelftest() {
        LOGGER.info("[凋零/自检] 凋灵最大生命={} 阶段二阈值={} 欲望阈值={} 欲望防抖={}tick",
                com.fusionwither.wither.WitherConfig.WITHER_MAX_HEALTH,
                com.fusionwither.wither.WitherConfig.PHASE2_THRESHOLD,
                com.fusionwither.wither.WitherConfig.DESIRE_PLAYER_HP_THRESHOLD,
                com.fusionwither.wither.WitherConfig.DESIRE_SWITCH_COOLDOWN_TICKS);
        LOGGER.info("[凋零/自检] 二阶段权重：玩家>50% 冲撞{}%/弹幕{}% ，玩家<=50% 冲撞{}%/弹幕{}%",
                com.fusionwither.wither.WitherConfig.WEIGHT_CRASH_NORMAL,
                com.fusionwither.wither.WitherConfig.WEIGHT_BARRAGE_NORMAL,
                com.fusionwither.wither.WitherConfig.WEIGHT_CRASH_HIGH,
                com.fusionwither.wither.WitherConfig.WEIGHT_BARRAGE_HIGH);
        LOGGER.info("[凋零/自检] 行为CD={}~{}tick 冲撞软CD={}tick 上升={}~{}格 骷髅上限={}",
                com.fusionwither.wither.WitherConfig.BEHAVIOR_COOLDOWN_MIN_TICKS,
                com.fusionwither.wither.WitherConfig.BEHAVIOR_COOLDOWN_MAX_TICKS,
                com.fusionwither.wither.WitherConfig.CRASH_SOFT_COOLDOWN_TICKS,
                com.fusionwither.wither.WitherConfig.CRASH_RISE_MIN_BLOCKS,
                com.fusionwither.wither.WitherConfig.CRASH_RISE_MAX_BLOCKS,
                com.fusionwither.wither.WitherConfig.PHASE2_SKELETON_MAX_ALIVE);
    }
}
