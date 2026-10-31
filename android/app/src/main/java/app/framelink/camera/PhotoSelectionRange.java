package app.framelink.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 一次滑动固定照片顺序与初始选择，回滑会恢复范围外原有状态 */
public final class PhotoSelectionRange {
    public final List<List<String>> groups;
    private final Set<String> original;
    private final int anchor;
    private final boolean selecting;
    private int lastIndex = -1;

    public PhotoSelectionRange(List<List<String>> groups, List<String> initial, int anchor) {
        if (anchor < 0 || anchor >= groups.size()) throw new IllegalArgumentException("滑动起点不在照片范围内");
        List<List<String>> snapshot = new ArrayList<>();
        for (List<String> group : groups) {
            if (group.isEmpty()) throw new IllegalArgumentException("照片分组不能为空");
            snapshot.add(Collections.unmodifiableList(new ArrayList<>(group)));
        }
        this.groups = Collections.unmodifiableList(snapshot);
        this.original = new LinkedHashSet<>(initial);
        this.anchor = anchor;
        boolean adding = false;
        for (String id : snapshot.get(anchor)) if (!original.contains(id)) adding = true;
        this.selecting = adding;
    }

    public int getLastIndex() { return lastIndex; }

    /** 范围计算始终从拖动起点快照开始，不累积上一次经过的照片 */
    public List<String> selectionAt(int index) {
        if (index < 0 || index >= groups.size()) throw new IllegalArgumentException("滑动位置不在照片范围内");
        lastIndex = index;
        Set<String> affected = new LinkedHashSet<>();
        for (int i = Math.min(anchor, index); i <= Math.max(anchor, index); i++) affected.addAll(groups.get(i));
        Set<String> result = new LinkedHashSet<>(original);
        if (selecting) result.addAll(affected); else result.removeAll(affected);
        return new ArrayList<>(result);
    }
}
