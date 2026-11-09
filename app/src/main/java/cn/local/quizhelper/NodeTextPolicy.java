package cn.local.quizhelper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Preserve independent parent text instead of dropping it whenever any child has text. */
final class NodeTextPolicy {
    private NodeTextPolicy() {}
    static boolean retain(String own, List<String> childValues) {
        own = Core.clean(own);
        if (own.isEmpty()) return false;
        Set<String> children = new HashSet<>();
        StringBuilder joined = new StringBuilder();
        for (String value : childValues) {
            String child = Core.clean(value);
            if (child.isEmpty()) continue;
            if (own.equals(child)) return false;
            children.add(child); joined.append(child);
        }
        if (own.replace(" ", "").equals(joined.toString().replace(" ", ""))) return false;
        int matched = 0, count = 0;
        for (String child : children) if (child.length() >= 2 && own.contains(child)) { matched += child.length(); count++; }
        return count < 2 || matched < own.length() * 0.6;
    }
}
