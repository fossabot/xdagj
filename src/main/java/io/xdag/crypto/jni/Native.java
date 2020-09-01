package io.xdag.crypto.jni;

import java.io.*;
import java.nio.file.Files;

import io.xdag.utils.SystemUtil;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.xdag.config.Config;

public class Native {

  private static final Logger logger = LoggerFactory.getLogger(Native.class);

  protected static File nativeDir;
  protected static boolean enabled = false;

  /**
   * Initializes the native libraries
   */
  protected static void init() {
    if (SystemUtil.is32bitJvm()) {
      // No more support for 32-bit systems
      return;
    }

    SystemUtil.OsName os = SystemUtil.getOsName();
    switch (os) {
      case LINUX:
        if (SystemUtil.getOsArch().equals("aarch64")) {
          enabled = loadLibrary("/native/Linux-aarch64/libdfs.so");
        } else {
          enabled = loadLibrary("/native/Linux-x86_64/libdfs.so");
        }
        break;
      case MACOS:
        enabled = loadLibrary("/native/Darwin-x86_64/libdfs.dylib");
        break;
      case WINDOWS:
        enabled = loadLibrary("/native/Windows-x86_64/libdfs.dll");
        break;
      default:
        break;
    }
  }

  /**
   * Loads a library file from bundled resource.
   *
   * @param resource
   * @return
   */
  protected static boolean loadLibrary(String resource) {
    try {
      if (nativeDir == null) {
        nativeDir = Files.createTempDirectory("native").toFile();
        nativeDir.deleteOnExit();
      }

      String name = resource.contains("/") ? resource.substring(resource.lastIndexOf('/') + 1) : resource;
      File file = new File(nativeDir, name);

      if (!file.exists()) {
        InputStream in = Native.class.getResourceAsStream(resource); // null pointer exception
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file));
        for (int c; (c = in.read()) != -1;) {
          out.write(c);
        }
        out.close();
        in.close();
      }

      System.load(file.getAbsolutePath());
      return true;
    } catch (Exception | UnsatisfiedLinkError e) {
      logger.warn("Failed to load native library: {}", resource, e);
      return false;
    }
  }

  // initialize library when the class loads
  static {
    init();
  }

  public static native int crypt_start();

  public static native int dnet_crypt_init();

  public static native int load_dnet_keys(byte[] keybytes, int length);

  public static native byte[] dfslib_encrypt_byte_sector(byte[] raw, int length, long sectorNo);

  public static native byte[] dfslib_uncrypt_byte_sector(
      byte[] encrypted, int length, long sectorNo);

  // public static native byte[] dfslib_uncrypt_array();

  // 这个是矿工之间的解密函数
  /* 参数1  加密的数据
   * 参数2   一共有多少个字段
   * 参数3   是第几个发送的 这里的sectorNo 是每一个字段加一次
   * */
  public static native byte[] dfslib_uncrypt_array(byte[] encrypted, int nfiled, long sectorNo);

  // 这个矿工的加密函数
  public static native byte[] dfslib_encrypt_array(byte[] uncrypted, int nfiled, long sectorNo);

  public static native long get_user_dnet_crypt();

  public static native long get_dnet_keys();

  public static native int set_user_dnet_crypt(String password);

  public static native void set_user_random(String randomKey);

  public static native byte[] make_dnet_keys(int keylen);

  // encrypt_wallet_key
  public static native byte[] encrypt_wallet_key(byte[] privkey, int n);

  public static native byte[] uncrypt_wallet_key(byte[] privkey, int n);

  public static native byte[] generate_random_array(byte[] array, long size);

  public static native byte[] generate_random_bytes(byte[] array, long size);

  public static native void dfslib_random_init();

  public static native void crc_init();

  public static native int verify_dnet_key(String password, byte[] data);

  public static native byte[] general_dnet_key(String password, String random);
}
