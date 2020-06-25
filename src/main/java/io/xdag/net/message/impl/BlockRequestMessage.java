package io.xdag.net.message.impl;

import io.xdag.net.message.AbstractMessage;
import io.xdag.net.message.NetStatus;
import io.xdag.net.message.XdagMessageCodes;
import io.xdag.utils.BytesUtils;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.spongycastle.util.Arrays;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;

import static io.xdag.config.Constants.DNET_PKT_XDAG;
import static io.xdag.core.XdagBlock.XDAG_BLOCK_SIZE;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_NONCE;

@EqualsAndHashCode(callSuper = false)
@Data
public class BlockRequestMessage extends AbstractMessage {

  public BlockRequestMessage(byte[] hash, NetStatus netStatus) {
    super(XdagMessageCodes.BLOCK_REQUEST, 0, 0, hash, netStatus);
    updateCrc();
  }

  public BlockRequestMessage(byte[] hash) {
    super(hash);
  }

  @Override
  public Class<?> getAnswerMessage() {
    return null;
  }

  @Override
  public byte[] getEncoded() {
    // TODO Auto-generated method stub
    return encoded;
  }

  @Override
  public String toString() {
    if (!parsed) {
      parse();
    }
    return "["
        + this.getCommand().name()
        + " starttime="
        + starttime
        + " endtime="
        + this.endtime
        + " hash="
        + Hex.toHexString(hash)
        + " netstatus="
        + netStatus;
  }

  @Override
  public XdagMessageCodes getCommand() {
    return XdagMessageCodes.BLOCK_REQUEST;
  }

  @Override
  public byte[] getHash() {
    return hash;
  }

  @Override
  public void encode() {
    parsed = true;
    encoded = new byte[512];
    int ttl = 1;
    long transportheader = (ttl << 8) | DNET_PKT_XDAG | (XDAG_BLOCK_SIZE << 16);
    long type = (codes.asByte() << 4) | XDAG_FIELD_NONCE.asByte();

    BigInteger diff = netStatus.getDifficulty();
    BigInteger maxDiff = netStatus.getMaxdifficulty();
    long nmain = netStatus.getNmain();
    long totalMainNumber = netStatus.getTotalnmain();
    long nblocks = netStatus.getNblocks();
    long totalBlockNumber = netStatus.getTotalnblocks();

    // TODO：后续根据ip替换
    String tmp =
        "04000000040000003ef47801000000007f000001611e7f000001b8227f000001" + "5f767f000001d49d";
    byte[] tmpbyte = Hex.decode(tmp); // net 相关

    // field 0 and field1
    byte[] first =
        BytesUtils.merge(
            BytesUtils.longToBytes(transportheader, true),
            BytesUtils.longToBytes(type, true),
            BytesUtils.longToBytes(starttime, true),
            BytesUtils.longToBytes(endtime, true));
    System.arraycopy(first, 0, encoded, 0, 32);
    hash = Arrays.reverse(hash);
    System.arraycopy(hash, 0, encoded, 32, 32);

    // field2 diff and maxdiff
    System.arraycopy(BytesUtils.bigIntegerToBytes(diff, 16, true), 0, encoded, 64, 16);
    System.arraycopy(BytesUtils.bigIntegerToBytes(maxDiff, 16, true), 0, encoded, 80, 16);

    // field3 nblock totalblock main totalmain
    System.arraycopy(BytesUtils.longToBytes(nblocks, true), 0, encoded, 96, 8);
    System.arraycopy(BytesUtils.longToBytes(totalBlockNumber, true), 0, encoded, 104, 8);
    System.arraycopy(BytesUtils.longToBytes(nmain, true), 0, encoded, 112, 8);
    System.arraycopy(BytesUtils.longToBytes(totalMainNumber, true), 0, encoded, 120, 8);

    System.arraycopy(tmpbyte, 0, encoded, 128, tmpbyte.length);
  }

  @Override
  public void parse() {
    if (parsed) {
      return;
    }
    starttime = BytesUtils.bytesToLong(encoded, 16, true);
    endtime = BytesUtils.bytesToLong(encoded, 24, true);
    BigInteger maxdifficulty = BytesUtils.bytesToBigInteger(encoded, 80, true);
    long totalnblocks = BytesUtils.bytesToLong(encoded, 104, true);
    long totalnmains = BytesUtils.bytesToLong(encoded, 120, true);
    int totalnhosts = BytesUtils.bytesToInt(encoded, 132, true);
    long maintime = BytesUtils.bytesToLong(encoded, 136, true);
    netStatus = new NetStatus(maxdifficulty, totalnblocks, totalnmains, totalnhosts, maintime);
    hash = new byte[32];
    System.arraycopy(encoded, 32, hash, 0, 24);
    parsed = true;
  }
}
