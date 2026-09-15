package com.piliplus.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 轻量 JSON 解析器：object→Map, array→List, string→String, number→Long/Double, bool→Boolean */
public final class Json2 {
    private final String s;
    private int i;

    private Json2(String s) { this.s = s; }

    public static Object parse(String s) {
        if (s == null) return null;
        Json2 p = new Json2(s);
        p.ws();
        return p.val();
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object val() {
        ws();
        if (i >= s.length()) return null;
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '\"') return str();
        if (c == 't') { i += 4; return Boolean.TRUE; }
        if (c == 'f') { i += 5; return Boolean.FALSE; }
        if (c == 'n') { i += 4; return null; }
        return num();
    }

    private Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (i < s.length()) {
            ws();
            String k = str();
            ws();
            if (i < s.length() && s.charAt(i) == ':') i++;
            m.put(k, val());
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == '}') { i++; break; }
            break;
        }
        return m;
    }

    private List<Object> arr() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (i < s.length()) {
            l.add(val());
            ws();
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            if (i < s.length() && s.charAt(i) == ']') { i++; break; }
            break;
        }
        return l;
    }

    private String str() {
        StringBuilder b = new StringBuilder();
        if (i < s.length() && s.charAt(i) == '\"') i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '\"') break;
            if (c == '\\' && i < s.length()) {
                char e = s.charAt(i++);
                if (e == 'n') b.append('\n');
                else if (e == 't') b.append('\t');
                else if (e == 'r') b.append('\r');
                else if (e == 'b') b.append('\b');
                else if (e == 'f') b.append('\f');
                else if (e == 'u') {
                    if (i + 4 <= s.length()) {
                        try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (Exception ex) { }
                        i += 4;
                    }
                } else b.append(e);
            } else b.append(c);
        }
        return b.toString();
    }

    private Object num() {
        int st = i;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
            else break;
        }
        String t = s.substring(st, i);
        if (t.isEmpty()) { i++; return null; }
        try {
            if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) return Double.parseDouble(t);
            return Long.parseLong(t);
        } catch (Exception e) { return t; }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }

    public static String str(Object o) { return o == null ? null : String.valueOf(o); }

    public static String str(Map<String, Object> m, String k) { return m == null ? null : str(m.get(k)); }

    public static long lng(Map<String, Object> m, String k) {
        if (m == null) return 0;
        Object o = m.get(k);
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) { try { return Long.parseLong((String) o); } catch (Exception e) { return 0; } }
        return 0;
    }
}
