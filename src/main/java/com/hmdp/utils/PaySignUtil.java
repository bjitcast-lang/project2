package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.HMac;
import cn.hutool.crypto.digest.HmacAlgorithm;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 模拟支付渠道的签名工具：HMAC-SHA256。
 * <p>
 * 签名内容为除 sign 以外的参数按 key 升序拼接成 {@code k1=v1&k2=v2}。
 * 生产环境应换成真实渠道的验签规则，密钥放配置中心/环境变量。
 */
public final class PaySignUtil {

    private PaySignUtil() {
    }

    public static String sign(Map<String, String> params, String secret) {
        HMac hmac = new HMac(HmacAlgorithm.HmacSHA256,
                secret.getBytes(StandardCharsets.UTF_8));
        return hmac.digestHex(buildContent(params));
    }

    public static boolean verify(Map<String, String> params, String secret) {
        String sign = params.get("sign");
        if (StrUtil.isBlank(sign)) {
            return false;
        }
        Map<String, String> copy = new TreeMap<>(params);
        copy.remove("sign");
        return sign.equalsIgnoreCase(sign(copy, secret));
    }

    private static String buildContent(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new TreeMap<>(params);
        return sorted.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .filter(entry -> !"sign".equals(entry.getKey()))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    /**
     * 便于测试/演示：构造不可变参数 Map。
     */
    public static Map<String, String> params(String... kv) {
        if (kv == null || kv.length % 2 != 0) {
            throw new IllegalArgumentException("参数必须成对出现");
        }
        Map<String, String> map = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return Collections.unmodifiableMap(map);
    }
}
