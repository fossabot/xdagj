package io.xdag.consensus;

import java.util.Random;

import org.junit.Before;
import org.junit.Test;

import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.core.BlockchainImpl;
import io.xdag.crypto.jni.Native;
import io.xdag.db.DatabaseFactory;
import io.xdag.db.DatabaseName;
import io.xdag.db.rocksdb.RocksdbFactory;
import io.xdag.db.store.AccountStore;
import io.xdag.db.store.BlockStore;
import io.xdag.db.store.OrphanPool;
import io.xdag.net.message.NetStatus;
import io.xdag.wallet.Wallet;
import io.xdag.wallet.WalletImpl;

/**
 * @ClassName Sync
 * @Description
 * @Author punk
 * @Date 2020/5/1 11:46
 * @Version V1.0
 **/
public class Sync {

    Config config = new Config();
    Wallet xdagWallet;
    Kernel kernel;
    DatabaseFactory dbFactory;

    //
    @Before
    public void setUp() throws Exception {
        config.setStoreMaxThreads(1);
        config.setStoreMaxOpenFiles(1024);
        config.setStoreDir("/Users/punk/testRocksdb/XdagDB");
        config.setStoreBackupDir("/Users/punk/testRocksdb/XdagDB/backupdata");

        kernel = new Kernel(config);
        dbFactory = new RocksdbFactory(config);

        BlockStore blockStore = new BlockStore(dbFactory.getDB(DatabaseName.INDEX),dbFactory.getDB(DatabaseName.BLOCK),dbFactory.getDB(DatabaseName.TIME),null);
        blockStore.reset();
        AccountStore accountStore = new AccountStore(xdagWallet,blockStore,dbFactory.getDB(DatabaseName.ACCOUNT));
        accountStore.reset();
        OrphanPool orphanPool = new OrphanPool(dbFactory.getDB(DatabaseName.ORPHANIND));
        orphanPool.reset();

        kernel.setBlockStore(blockStore);
        kernel.setAccountStore(accountStore);
        kernel.setOrphanPool(orphanPool);
        Native.init();
        if(Native.dnet_crypt_init() < 0){
            throw new Exception("dnet crypt init failed");
        }
        xdagWallet = new WalletImpl();
        xdagWallet.init(config);
        kernel.setWallet(xdagWallet);
        kernel.setNetStatus(new NetStatus());
        BlockchainImpl blockchain = new BlockchainImpl(kernel,dbFactory);
        kernel.setBlockchain(blockchain);
    }

    //Xdag PoW可以看作状态机 1.开始出块 2.接收到share更新块 3.接收到新pretop 回到1 4.timeout发送区块 回到1
    @Test
    public void TestPoW() throws InterruptedException {
        XdagPow pow = new XdagPow(kernel);
        pow.onStart();

        byte[] minShare = new byte[32];
        new Random().nextBytes(minShare);

        Thread sendPretop = new Thread(()->{
            try {
                for (int i = 0;i<2;i++) {
                    Thread.sleep(6000);
                    pow.receiveNewPretop(minShare);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
        sendPretop.start();
        sendPretop.join();
        pow.stop();
    }

}
