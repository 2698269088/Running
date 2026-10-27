package top.mcocet.running.flight;

/**
 * 飞行弹道推演所需的最小世界查询面（对齐 MovementSync 的 {@code World} 在
 * {@code GlidingPhysics.step} 中的用法：只读、按格判定，无副作用）。
 *
 * <p>由 {@link BlockWorldQuery} 适配到 Running 的 {@code RunningWorld} +
 * {@code BlockNameTable}；线程约定与 {@code PathWorld} 一致：路径计算线程与
 * 引擎线程只读，区块数据由网络线程写入。</p>
 */
public interface FlightWorldQuery {

    /** 该格区块是否已加载（未加载的格子必须让推演判 UNKNOWN，绝不"盲飞越界"） */
    boolean isLoaded(int x, int y, int z);

    /** 该格是否阻挡移动（实体碰撞盒与之相交即视为撞墙/落地） */
    boolean isSolid(int x, int y, int z);

    /** 该格是否可作为落点台面（有碰撞顶面、非流体、非危险方块） */
    boolean isStandable(int x, int y, int z);

    /** 该格是否为水（滑翔触水即停：安全但飞行中断，绝不当作可继续掠过） */
    boolean isWater(int x, int y, int z);

    /** 该格是否为岩浆（危险：撞上判致命） */
    boolean isLava(int x, int y, int z);

    /** 世界最低高度（推演触底仍未着陆 → UNKNOWN，虚空永不接受） */
    int minY();

    /** 世界最高高度（含） */
    int maxY();
}
