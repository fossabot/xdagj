package io.xdag.consensus;

import static io.xdag.utils.FastByteComparisons.compareTo;
import static org.spongycastle.util.Arrays.reverse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import io.xdag.Kernel;
import io.xdag.core.Block;
import io.xdag.core.Blockchain;
import io.xdag.core.XdagBlock;
import io.xdag.core.XdagField;
import io.xdag.mine.MinerChannel;
import io.xdag.mine.manager.AwardManager;
import io.xdag.mine.manager.MinerManager;
import io.xdag.mine.miner.MinerCalculate;
import io.xdag.net.XdagChannel;
import io.xdag.net.manager.XdagChannelManager;
import io.xdag.net.message.Message;
import io.xdag.net.message.impl.NewBlockMessage;
import io.xdag.utils.XdagSha256Digest;
import io.xdag.utils.XdagTime;

public class XdagPow implements PoW {

	private static final Logger logger = LoggerFactory.getLogger(XdagPow.class);

	private Kernel kernel;
	/** 事件队列 */
	protected BlockingQueue<Event> events = new LinkedBlockingQueue<>();
	protected Status status;

	protected Timer timer;
	protected Broadcaster broadcaster;

	protected Block generateBlock;
	protected byte[] minShare;
	protected byte[] minHash;
	protected Task currentTask;
	protected XdagSha256Digest currentTaskDigest;
	protected long sendTime;

	protected XdagChannelManager channelMgr;
	protected Blockchain blockchain;

	protected Thread consThread;

	/**
	 * 存放的是过去十六个区块的hash
	 */
	protected List<byte[]> blockHashs = new CopyOnWriteArrayList<byte[]>();

	/**
	 * 存放的是最小的hash
	 */
	protected List<byte[]> minShares = new CopyOnWriteArrayList<>(new ArrayList<>(16));

	// 引入矿工与奖励
	protected AwardManager awardManager;
	protected MinerManager minerManager;
	protected long taskIndex = 0;

	public XdagPow(Kernel kernel) {
		this.kernel = kernel;

		this.blockchain = kernel.getBlockchain();
		this.channelMgr = kernel.getChannelManager();

		this.timer = new Timer();
		this.broadcaster = new Broadcaster();

		this.status = Status.STOPPED;
		this.minerManager = kernel.getMinerManager();
		this.awardManager = kernel.getAwardManager();

		consThread = new Thread(this::start, "consensus");
	}

	@Override
	public void start() {
		// 当状态 production_on false 同时 stop_mining false 不执行下面的代码
		// 当stop_mining false时执行

		// logger.info("Strat producing blocks");
		System.out.println("Strat producing blocks");

		if (status == Status.STOPPED && !Thread.interrupted()) {
			logger.debug("====Main block thread run=====");
			status = Status.RUNNING;

			minerManager = kernel.getMinerManager();
			kernel.getPoolMiner();

			// 容器的初始化
			for (int i = 0; i < 16; i++) {
				blockHashs.add(null);
				minShares.add(null);
			}

			timer.start();
			broadcaster.start();

			newBlock();
			eventLoop();

			logger.debug("====Main Block thread stopped====");
		}
	}

	@Override
	public void stop() {
		logger.debug("pow stop");
		if (status != Status.STOPPED) {
			// interrupt sync
			if (status == Status.SYNCING) {
//                syncMgr.stop();
			}

			timer.stop();
			broadcaster.stop();

			status = Status.STOPPED;
			Event ev = new Event(Event.Type.STOP);
			if (!events.offer(ev)) {
				logger.error("Failed to add an event to message queue: ev = {}", ev);
			}

			if (consThread != null) {
				consThread.interrupt();
			}
		}
	}

	public void newBlock() {
		logger.debug("===New Block===");
		sendTime = XdagTime.getMainTime();
		resetTimeout(sendTime);
		generateBlock = generateBlock();
//        status = Status.BLOCK_PRODUCTION_ON;
	}

	public Block generateBlock() {
		logger.debug("Generate New Block sendTime:" + Long.toHexString(sendTime));
		logger.debug("=Start time:" + Long.toHexString(XdagTime.getCurrentTimestamp()));
		// 固定sendtime
		Block block = blockchain.createNewBlock(null, null, true);
		block.signOut(kernel.getWallet().getDefKey().ecKey);

		minShare = new byte[32];
		// 初始minshare 初始minhash
		new Random().nextBytes(minShare);
		block.setNonce(minShare);
		minHash = block.calcHash();
		// 发送给矿工
		currentTask = createTaskByNewBlock(block);
		logger.debug("Send Task to Miners");

		// 更新poolminer的贡献
		minerManager.updateNewTaskandBroadcast(currentTask);
		awardManager.onNewTask(currentTask);

		MinerCalculate.calculateNopaidShares(awardManager.getPoolMiner(), minHash, block.getTimestamp());

		return block;
	}

	protected void resetTimeout(long timeout) {
		timer.timeout(timeout);
		events.removeIf(e -> e.type == Event.Type.TIMEOUT);
	}

	public class Timer implements Runnable {
		private long timeout;
		private Thread t;

		@Override
		public void run() {
			while (!Thread.currentThread().isInterrupted()) {
				synchronized (this) {
					if (timeout != -1 && XdagTime.getCurrentTimestamp() > timeout) {
						events.add(new Event(Event.Type.TIMEOUT));
						timeout = -1;
						continue;
					}
				}
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

		public synchronized void start() {
			if (t == null) {
				t = new Thread(this, "pow-timer");
				t.start();
			}
		}

		public synchronized void stop() {
			if (t != null) {
				try {
					t.interrupt();
					t.join(10000);
				} catch (InterruptedException e) {
					logger.warn("Failed to stop consensus timer");
					Thread.currentThread().interrupt();
				}
				t = null;
			}
		}

		public synchronized void timeout(long sendtime) {
			if (sendtime < 0) {
				throw new IllegalArgumentException("Timeout can not be negative");
			}
			timeout = sendtime;
		}

		public synchronized void clear() {
			timeout = -1;
		}
	}

	/**
	 * Pause the pow manager, and do synchronization.
	 */
	protected void sync(long topDiff) {
		if (status == Status.RUNNING) {
			status = Status.SYNCING;
			if (status != Status.STOPPED) {
				status = Status.RUNNING;
			}
		}
	}

	@Override
	public boolean isRunning() {
		return status == Status.RUNNING;
	}

	/** 每收到一个miner的信息 之后都会在这里进行一次计算 */
	@Override
	public void receiveNewShare(MinerChannel channel, Message msg) {
		logger.debug("Receive share From PoolChannel");
		if (!isRunning()) {
			return;
		}

		XdagField shareInfo = new XdagField(msg.getEncoded());
		logger.debug("shareinfo:" + Hex.toHexString(shareInfo.getData()));
		events.add(new Event(Event.Type.NEW_SHARE, shareInfo, channel));
	}

	@Override
	public void receiveNewPretop(byte[] pretop) {
		logger.debug("ReceiveNewPretop");
		if (!isRunning()) {
			return;
		}
		events.add(new Event(Event.Type.NEW_PRETOP, pretop));
	}

	protected void eventLoop() {
		while (!Thread.currentThread().isInterrupted()) {
			try {
				Event ev = events.take();
				if (status != Status.RUNNING) {
					continue;
				}
				switch (ev.getType()) {
				case NEW_DIFF:
					sync(ev.getData());
					break;
				case STOP:
					return;
				case NEW_SHARE:
					onNewShare(ev.getData(), ev.getChannel());
					break;
				case TIMEOUT:
					onTimeout();
					break;
				case NEW_PRETOP:
					onNewPreTop();
					break;
				default:
					break;
				}

			} catch (InterruptedException e) {
				logger.debug("BftManager got interrupted");
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				logger.warn("Unexpected exception in event loop", e);
			}
		}
	}

	protected void onNewShare(XdagField shareInfo, MinerChannel channel) {
		XdagField share = shareInfo;
		try {
			XdagSha256Digest digest = new XdagSha256Digest(currentTaskDigest);
			byte[] hash = digest.sha256Final(reverse(share.getData()));

			// awardManager.calculateNopaidShares(channel.getMiner(),hash,currentTask.getTaskTime());
			MinerCalculate.updateMeanLogDiff(channel, currentTask, hash);
			MinerCalculate.calculateNopaidShares(channel, hash, currentTask.getTaskTime());

			if (compareTo(hash, 0, 32, minHash, 0, 32) < 0) {
				minHash = hash;
				minShare = reverse(share.getData());
				byte[] hashlow = new byte[32];
				System.arraycopy(minHash, 8, hashlow, 8, 24);
				generateBlock.setNonce(minShare);
				generateBlock.setHash(minHash);
				generateBlock.setHashLow(hashlow);

				// 把计算出来的最后的结果放到nonce里面
				int index = (int) ((currentTask.getTaskTime() >> 16) & 0xf);
				// int index = (int) ((currentTask.getTaskTime() >> 16) & 7);
				minShares.set(index, minShare);
				blockHashs.set(index, minHash);

				logger.debug("New MinHash :" + Hex.toHexString(minHash));
				logger.debug("New MinShare :" + Hex.toHexString(minShare));
				logger.debug("区块放入的对应的hash 为【{}】", Hex.toHexString(generateBlock.getHash()));
				logger.debug("区块放入的对应的hash 为【{}】", Hex.toHexString(generateBlock.getHashLow()));
				logger.debug("对应的区块【{}】", Hex.toHexString(generateBlock.toBytes()));
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	protected void onTimeout() {
		// logger.info("Broadcast locally generated blockchain, waiting to be verified.
		// block hash = [{}]",Hex.toHexString(generateBlock.getHash()));
		System.out.println("Broadcast locally generated blockchain, waiting to be verified. block hash =["
				+ Hex.toHexString(generateBlock.getHash()) + "]");
		// 发送区块 如果有的话 然后开始生成新区块
		logger.debug("添加并发送现有区块 开始生成新区块 sendTime:" + Long.toHexString(sendTime));
		logger.debug("End Time:" + Long.toHexString(XdagTime.getCurrentTimestamp()));
		logger.debug("发送区块:" + Hex.toHexString(generateBlock.toBytes()));
		logger.debug("发送区块hash:" + Hex.toHexString(generateBlock.getHashLow()));
		logger.debug("发送区块hash:" + Hex.toHexString(generateBlock.getHash()));
		synchronized (kernel.getBlockchain()) {
			kernel.getBlockchain().tryToConnect(new Block(new XdagBlock(generateBlock.toBytes())));
		}
		awardManager.payAndaddNewAwardBlock(minShare.clone(), generateBlock.getHash().clone(),
				generateBlock.getTimestamp());

		broadcaster.broadcast(
				new NewBlockMessage(new Block(new XdagBlock(generateBlock.toBytes())), kernel.getConfig().getTTL()));

		newBlock();
	}

	protected void onNewPreTop() {
		logger.debug("Receive New PreTop");
		newBlock();
	}

	public class Broadcaster implements Runnable {
		private final BlockingQueue<NewBlockMessage> queue = new LinkedBlockingQueue<>();

		private Thread t;

		@Override
		public void run() {
			while (!Thread.currentThread().isInterrupted()) {
				try {
					NewBlockMessage msg = queue.take();
					logger.debug("myron  queue 取出的区块 hash【{}】", Hex.toHexString(msg.getBlock().getHash()));
					logger.debug("myron  queue 取出的区块数据【{}】", Hex.toHexString(msg.getBlock().getHash()));
					List<XdagChannel> channels = channelMgr.getActiveChannels();
					if (channels != null) {
						// 全部广播
						for (XdagChannel channel : channels) {
							if (channel.isActive()) {
								channel.getXdag().sendMessage(msg);
							}
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}

		public synchronized void start() {
			if (t == null) {
				t = new Thread(this, "pow-relay");
				t.start();
			}
		}

		public synchronized void stop() {
			if (t != null) {
				try {
					t.interrupt();
					t.join();
				} catch (InterruptedException e) {
					logger.error("Failed to stop consensus broadcaster");
					Thread.currentThread().interrupt();
				}
				t = null;
			}
		}

		public void broadcast(NewBlockMessage msg) {
			if (!queue.offer(msg)) {
				logger.error("Failed to add a message to the broadcast queue: msg = {}", msg);
			}
		}
	}

	public Task createTaskByNewBlock(Block block) {
		Task newTask = new Task();
		XdagField[] task = new XdagField[2];
		task[1] = block.getXdagBlock().getField(14);
		byte[] data = new byte[448];
		System.arraycopy(block.getXdagBlock().getData(), 0, data, 0, 448);
		currentTaskDigest = new XdagSha256Digest();
		try {
			currentTaskDigest.sha256Update(data);
			byte[] state = currentTaskDigest.getState();
			task[0] = new XdagField(state);
			currentTaskDigest.sha256Update(block.getXdagBlock().getField(14).getData());
		} catch (IOException e) {
			e.printStackTrace();
		}
		newTask.setTask(task);
		newTask.setTaskTime(XdagTime.getMainTime());
		newTask.setTaskIndex(taskIndex++);
		return newTask;
	}

	public enum Status {
		/** 停止 */
		STOPPED, RUNNING, SYNCING, BLOCK_PRODUCTION_ON
	}

	public static class Event {
		public enum Type {
			/**
			 * Stop signal
			 */
			STOP,
			/**
			 * Received a timeout signal.
			 */
			TIMEOUT,
			/**
			 * Received a new share message.
			 */
			NEW_SHARE,
			/**
			 * Received a new pretop message.
			 */
			NEW_PRETOP,
			/**
			 * Received a new largest diff message.
			 */
			NEW_DIFF,
		}

		private final Type type;
		private final Object data;
		private Object channel;

		public Event(Type type) {
			this(type, null);
		}

		public Event(Type type, Object data) {
			this.type = type;
			this.data = data;
		}

		public Event(Type type, Object data, Object channel) {
			this.type = type;
			this.data = data;
			this.channel = channel;
		}

		public Type getType() {
			return type;
		}

		@SuppressWarnings("unchecked")
		public <T> T getData() {
			return (T) data;
		}

		@SuppressWarnings("unchecked")
		public <T> T getChannel() {
			return (T) channel;
		}

		@Override
		public String toString() {
			return "Event [type=" + type + ", data=" + data + "]";
		}
	}

	public void onStart() {
		consThread.start();
	}

}
