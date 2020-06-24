package io.xdag.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.spongycastle.util.encoders.Hex;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class IpUtils {

    private static final String IPV6_INPUT_FORMAT = "^\\[(.*)\\]:([0-9]{1,})";
    private static final String IPV4_INPUT_FORMAT = "^([^:]*):([0-9]{1,})";
    private static final Pattern ipv6Pattern = Pattern.compile(IPV6_INPUT_FORMAT);
    private static final Pattern ipv4Pattern = Pattern.compile(IPV4_INPUT_FORMAT);


    public static InetSocketAddress parseAddress(String address) {
        if(StringUtils.isBlank(address)) {
            return null;
        }
        
        Matcher matcher = ipv6Pattern.matcher(address);
        if(matcher.matches()) {
            return parseMatch(matcher);
        }

        matcher = ipv4Pattern.matcher(address);
        if (matcher.matches() && matcher.groupCount() == 2) {
            return parseMatch(matcher);
        }

        log.debug("Invalid address: {}. For ipv6 use de convention [address]:port. For ipv4 address:port", address);
        return null;
    }

    public static List<InetSocketAddress> parseAddresses(List<String> addresses) {
        List<InetSocketAddress> result = new ArrayList<>();
        for(String a : addresses) {
            InetSocketAddress res = parseAddress(a);
            if (res != null) {
                result.add(res);
            }
        }
        return result;
    }

    private static InetSocketAddress parseMatch(Matcher matcher) {
        return new InetSocketAddress(matcher.group(1), Integer.valueOf(matcher.group(2)));
    }

    /**
     * ip地址转换成16进制long
     * @param ipString
     * @return
     */
    public static byte[] ipToLong(String ipString) {
        if(StringUtils.isBlank(ipString)){
            return null;
        }
        String[] ip=ipString.split("\\.");
        StringBuffer sb=new StringBuffer();
        for (String str : ip) {
            sb.append(Integer.toHexString(Integer.parseInt(str)));
        }
        return Hex.decode(sb.toString());

    }

	
}
