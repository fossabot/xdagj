package io.xdag.mine.manager;

import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_IN;
import static io.xdag.core.XdagField.FieldType.XDAG_FIELD_OUT;
import static java.lang.Math.E;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import io.xdag.Kernel;
import io.xdag.config.Config;
import io.xdag.consensus.Task;
import io.xdag.core.Address;
import io.xdag.core.Block;
import io.xdag.core.BlockWrapper;
import io.xdag.core.Blockchain;
import io.xdag.crypto.ECKey;
import io.xdag.mine.miner.Miner;
import io.xdag.mine.miner.MinerStates;
import io.xdag.utils.BigDecimalUtils;
import io.xdag.utils.ByteArrayWrapper;
import io.xdag.utils.BytesUtils;
import io.xdag.utils.FastByteComparisons;
import io.xdag.wallet.Wallet;
import lombok.Setter;

/**
 * @ClassName AwardManagerImpl
 * @Description
 * @Author punk
 * @Date 2020/5/31 15:32
 * @Version V1.0
 **/
public class AwardManagerImpl implements AwardManager {
	private static final Logger logger = LoggerFactory.getLogger(AwardManagerImpl.class);

	/** 每一轮的确认数是16 */
	private static final int CONFIRMATIONS_COUNT = 16;

	private final double DBL = 2.2204460492503131E-016;

	protected Miner poolMiner;

	private List<Miner> miners;

	@Setter
	private MinerManager minerManager;

	/** 内部类 用于计算支付数据 */
	public static class PayData {
		// 整个区块用于支付的金额
		long balance;
		// 每一次扣除后剩余的钱 回合所用矿工平分
		long unusedBalance;
		// 矿池自己的收入
		long poolFee;
		// 出块矿工的奖励
		long minerReward;
		// 参与挖矿的奖励
		long directIncome;
		// 基金会的奖励
		long fundIncome;
		// 所有矿工diff 的难度之和
		double diffSums;
		// 所有矿工prevdiff的难度之和
		double prevDiffSums;
		// 记录奖励矿工的位置
		byte[] rewardMiner = null;
	}

	// 定义每一个部分的收益占比
	/** 矿池自己的收益 */
	private static double poolRation;

	/** 出块矿工占比 */
	private static double minerRewardRation;

	/** 基金会的奖励 */
	private static double fundRation;

	/** 矿工的参与奖励 */
	private static double directRation;

	/**
	 * 存放的是过去十六个区块的hash
	 */
	protected List<ByteArrayWrapper> blockHashs = new CopyOnWriteArrayList<ByteArrayWrapper>();
	protected List<ByteArrayWrapper> minShares = new CopyOnWriteArrayList<>(new ArrayList<>(16));
	protected long currentTaskTime;
	protected long currentTaskIndex;

	private Kernel kernel;
	private Blockchain blockchain;
	private Wallet xdagWallet;
	protected Config config;

	public AwardManagerImpl(Kernel kernel) {
		this.kernel = kernel;
		this.config = kernel.getConfig();
		this.blockchain = kernel.getBlockchain();
		this.xdagWallet = kernel.getWallet();
		this.poolMiner = kernel.getPoolMiner();
		this.minerManager = kernel.getMinerManager();
		init();

		setPoolConfig();
	}

	public void init() {
		// 容器的初始化
		for (int i = 0; i < 16; i++) {
			blockHashs.add(null);
			minShares.add(null);
		}
	}

	/** 给矿池设置一些支付上的参数 */
	private void setPoolConfig() {

		poolRation = BigDecimalUtils.div(config.getPoolRation(), 100);
		if (poolRation < 0) {
			poolRation = 0;
		} else if (poolRation > 1) {
			poolRation = 1;
		}

		minerRewardRation = BigDecimalUtils.div(config.getRewardRation(), 100);
		if (minerRewardRation < 0) {
			minerRewardRation = 0;
		} else if (poolRation + minerRewardRation > 1) {
			minerRewardRation = 1 - poolRation;
		}

		directRation = BigDecimalUtils.div(config.getDirectRation(), 100);
		if (directRation < 0) {
			directRation = 0;
		} else if (poolRation + minerRewardRation + directRation > 1) {
			directRation = 1 - poolRation - minerRewardRation;
		}

		fundRation = BigDecimalUtils.div(config.getFundRation(), 100);
		if (fundRation < 0) {
			fundRation = 0;
		} else if (poolRation + minerRewardRation + directRation + fundRation > 1) {
			fundRation = 1 - poolRation - minerRewardRation - directRation;
		}

	}

	/**
	 * 对主块进行支付 并且设置当前这一轮主块的相关信息
	 * 
	 * @param share        接收到的满足最小hash 的shares
	 * @param hash         主块的hash
	 * @param generateTime
	 */

	@Override
	public void payAndaddNewAwardBlock(byte[] share, byte[] hash, long generateTime) {
		// 支付矿工奖励
		logger.debug("Pay miner");
		payMiners(generateTime);
		logger.debug("set index:" + (int) ((generateTime >> 16) & 0xf));
		blockHashs.set((int) ((generateTime >> 16) & 0xf), new ByteArrayWrapper(hash));
		minShares.set((int) ((generateTime >> 16) & 0xf), new ByteArrayWrapper(share));
	}

	@Override
	public void setPoolMiner(byte[] hash) {
		this.poolMiner = new Miner(hash);
	}

	@Override
	public Miner getPoolMiner() {
		return poolMiner;
	}

	@Override
	public void onNewTask(Task task) {
		currentTaskTime = task.getTaskTime();
		currentTaskIndex = task.getTaskIndex();
	}

	/**
	 *
	 * @param time 时间段
	 * @return 错误代码 -1 没有矿工参与挖矿 不进行支付操作 -2 找不到对应的区块hash 或者 结果nonce -3 找不到对应的区块 -4
	 *         区块余额不足，不是主块不进行支付 -5 余额分配失败 -6 找不到签名密钥 -7 难度太小 不予支付
	 */
	public int payMiners(long time) {
		logger.debug("this is payMiners........");

		// 获取到的是当前任务的对应的+1的位置 以此延迟16轮
		int index = (int) (((time >> 16) + 1) & 0xf);
		// int index = (int ) (((time>> 16)+1)& 7);

		int keyPos = -1;

		// 记录矿工的数量
		int minerCounts = 0;
		// 初始化一个记录支付情况的paydata
		PayData payData = new PayData();

		// 每一个区块最多可以放多少交易 这个要由密钥的位置来决定
		int payminersPerBlock = 0;

		miners = new ArrayList<>();
		// 统计矿工的数量
		for (Miner miner : minerManager.getActivateMiners().values()) {
			miners.add(miner);
			minerCounts++;
		}

		// 没有矿工挖矿，直接全部收入交由地址块
		if (minerCounts <= 0) {
			logger.debug("no miners");
			return -1;
		}

		// 获取到要计算的hash 和对应的nocne
		byte[] hash = blockHashs.get(index) == null ? null : blockHashs.get(index).getData();
		byte[] nonce = minShares.get(index) == null ? null : minShares.get(index).getData();

		if (hash == null || nonce == null) {
			logger.debug("找不到对应的hash or nonce ,hash为空吗[{}],nonce为空吗[{}]", hash == null, nonce == null);
			return -2;
		}

		// 获取到这个区块 查询时要把前面的置0
		byte[] hashlow = BytesUtils.fixBytes(hash, 8, 24);
		logger.debug("要查找的区块的hash【{}】", Hex.toHexString(hashlow));
		Block block = blockchain.getBlockByHash(hashlow, false);

		keyPos = kernel.getBlockStore().getBlockKeyIndex(hashlow);

		if (keyPos < 0) {
			keyPos = blockchain.getMemAccount().get(new ByteArrayWrapper(hash)) != null
					? blockchain.getMemAccount().get(new ByteArrayWrapper(hash))
					: -2;
		}

		if (block == null) {
			logger.debug("找不到对应的区块");
			return -3;
		}

		payData.balance = block.getAmount();
		logger.debug("要支付的区块的金额为 ：[{}]", payData.balance);

		if (payData.balance <= 0) {
			logger.debug("这个块不是主块，不可用于支付");
			return -4;
		}

		// 计算矿池部分的收益
		payData.poolFee = BigDecimalUtils.mul(payData.balance, poolRation);
		payData.unusedBalance = payData.balance - payData.poolFee;

		// 进行各部分奖励的计算
		if (payData.unusedBalance <= 0) {
			logger.debug("余额不足");
			return -5;
		}

		if (keyPos < 0) {
			logger.debug("不存在对应的密钥。。。。");
			return -6;
		}

		// 决定一个区块是否需要再有一个签名字段
		// todo 这里不够严谨把 如果时第三把第四把呢
		if (xdagWallet.getKey_internal().size() - 1 == keyPos) {
			payminersPerBlock = 12;
		} else {
			payminersPerBlock = 10;
		}

		poolMiner.setDiffSum(time, 0.0);
		poolMiner.setPrevDiffSum(time, minerCounts);

		// 真正处理的数据是在这一块
		// 这个函数会把每一个矿工的计算出来的diff 和prevdiff 都存在上面的列表
		// prevDiffSum 是每一个矿工的本轮计算的难度 加上以前所有难度之和
		// diffs 是本轮进行的计算
		double prevDiffSum = precalculatePayments(hash, nonce, index, payData, time);

		if (prevDiffSum <= DBL) {
			logger.debug("diff is too low");
			return -7;
		}

		// 通过precalculatePay后计算出的数据 进行计算
		doPayments(hashlow, payminersPerBlock, payData, keyPos, time);

		return 0;
	}

	private double precalculatePayments(byte[] hash, byte[] nonce, int index, PayData payData, long time) {

		logger.debug("precalculatePayments........");
		// 计算矿池中矿工的奖励

		for (Miner miner : miners) {
			countpay(miner, index, payData, time);

			if (payData.rewardMiner == null
					&& (FastByteComparisons.compareTo(nonce, 8, 24, miner.getAddressHash(), 8, 24) == 0)) {
				payData.rewardMiner = new byte[32];

				payData.rewardMiner = miner.getAddressHash();

				// 有可以出块的矿工 分配矿工的奖励
				payData.minerReward = BigDecimalUtils.mul(payData.balance, minerRewardRation);
				payData.unusedBalance -= payData.minerReward;

			}

		}

		// 清0 代表这一轮的计算已经完成
		for (Miner miner : miners) {
			if (miner.getMaxDiffs(index) > 0) {
				miner.setMaxDiffs(index, 0.0);
			}

			miner.setPrevDiffCounts(0);
			miner.setPrevDiff(0.0);
		}

		// 要进行参与奖励的支付
		if (payData.diffSums > 0) {
			payData.minerReward = BigDecimalUtils.mul(payData.balance, directRation);
			payData.unusedBalance -= payData.directIncome;
		}

		return payData.prevDiffSums;
	}

	/**
	 * 对矿工之前的挖矿的难度进行计算 主要是用于形成支付的权重
	 * 
	 * @param miner 矿工的结构体
	 * @param index 对应的要计算的难度编号
	 */
	private void countpay(Miner miner, int index, PayData payData, long time) {

		double diffSum = 0.0;
		int diffCount = 0;

		// 这里是如果矿工的
		if (miner.getMinerStates() == MinerStates.MINER_ARCHIVE &&
		// c好过十六个时间戳没有进行计算
				currentTaskTime - miner.getTaskTime() > 65536 * 16) {
			// 这个主要是为了超过十六个快没有挖矿 所以要给他支付
			diffSum += processOutdatedMiner(miner);
			diffCount++;
		} else if (miner.getMaxDiffs(index) > 0) {
			diffSum += miner.getMaxDiffs(index);
			++diffCount;
		}

		double diff = diffToPay(diffSum, diffCount);

		diffSum += miner.getPrevDiff();
		diffCount += miner.getPrevDiffCounts();

		miner.setPrevDiff(0.0);

		miner.setDiffSum(time, diff);
		miner.setPrevDiffSum(time, diffToPay(diffSum, diffCount));

		payData.diffSums += diff;

		payData.prevDiffSums += diffToPay(diffSum, diffCount);

	}

	public void doPayments(byte[] hash, int paymentsPerBlock, PayData payData, int keyPos, long time) {
		ArrayList<Address> receipt = new ArrayList<>(paymentsPerBlock - 1);

		Map<Address, ECKey> inputMap = new HashMap<>();
		Address input = new Address(hash, XDAG_FIELD_IN);
		ECKey inputKey = xdagWallet.getKeyByIndex(keyPos);
		inputMap.put(input, inputKey);

		long payAmount = 0L;

		/**
		 * 基金会和转账矿池部分代码 暂时不用 //先支付给基金会 long fundpay =
		 * BasicUtils.xdag2amount(payData.fundIncome); byte[] fund =
		 * BytesUtils.fixBytes(BasicUtils.address2Hash(Constants.FUND_ADDRESS),8,24);
		 *
		 * receipt.add(new
		 * Address(fund,XDAG_FIELD_OUT,BasicUtils.xdag2amount(payData.fundIncome)));
		 * payAmount += payData.fundIncome;
		 *
		 *
		 * //支付给矿池 实际上放着不动 就属于矿池 receipt.add(new
		 * Address(poolMiner.getAddressLow(),XDAG_FIELD_OUT,payData.poolFee)); payAmount
		 * += payData.poolFee;
		 */

		// 不断循环 支付给矿工
		for (Miner miner : miners) {

			// 保存的是一个矿工所有的收入
			long paymentSum = 0L;

			// 根据以前的情况分发奖励
			if (payData.prevDiffSums > 0) {
				double per = BigDecimalUtils.div(miner.getPrevDiffSum(time), payData.prevDiffSums);
				// paymentSum += (long)payData.unusedBalance * per;
				paymentSum += BigDecimalUtils.mul(payData.unusedBalance, per);
				logger.debug("这个矿工的prevDiff为【{}】，占的比例为【{}】,支付的金额为【{}】", miner.getPrevDiffSum(time), per, paymentSum);

			}

			// 计算当前这一轮
			if (payData.diffSums > 0) {
				double per = BigDecimalUtils.div(miner.getDiffSum(time), payData.diffSums);
				// paymentSum += (long)payData.directIncome * per;
				paymentSum += BigDecimalUtils.mul(payData.directIncome, per);
				logger.debug("这个矿工的Diff为【{}】，占的比例为【{}】,支付的金额为【{}】", miner.getDiffSum(time),
						BigDecimalUtils.div(miner.getDiffSum(time), payData.diffSums), paymentSum);
			}

			if (payData.rewardMiner != null
					&& FastByteComparisons.compareTo(payData.rewardMiner, 8, 24, miner.getAddressHash(), 8, 24) == 0) {
				paymentSum += payData.minerReward;
				logger.debug("获取到奖励的矿工的地址为【{}】", Hex.toHexString(miner.getAddressHaashLow()));
			}

			if (paymentSum < 0.000000001) {
				continue;
			}

			payAmount += paymentSum;

			receipt.add(new Address(miner.getAddressHaashLow(), XDAG_FIELD_OUT, paymentSum));

			if (receipt.size() == paymentsPerBlock) {

				transaction(hash, receipt, payAmount, keyPos);
				payAmount = 0L;
				receipt.clear();
			}

		}

		if (receipt.size() > 0) {

			transaction(hash, receipt, payAmount, keyPos);
			payAmount = 0L;
			receipt.clear();
		}

	}

	public void transaction(byte[] hashLow, ArrayList<Address> receipt, long payAmount, int keypos) {

		logger.debug("汇总的金额为【{}】", payAmount);
		for (Address address : receipt) {
			logger.debug("要支付的数据为：【{}】", Hex.toHexString(address.getData()));
		}

		Map<Address, ECKey> inputMap = new HashMap<>();

		Address input = new Address(hashLow, XDAG_FIELD_IN, payAmount);
		ECKey inputKey = xdagWallet.getKeyByIndex(keypos);
		inputMap.put(input, inputKey);

		Block block = blockchain.createNewBlock(inputMap, receipt, false);

		if (inputKey.equals(xdagWallet.getDefKey().ecKey)) {
			block.signOut(inputKey);
		} else {
			block.signIn(inputKey);
			block.signOut(xdagWallet.getDefKey().ecKey);
		}
		logger.debug("支付块的hash【{}】", Hex.toHexString(block.getHash()));

		// todo 需要验证还是直接connect
		kernel.getSyncMgr().validateAndAddNewBlock(new BlockWrapper(block, 5, null));
		// kernel.getBlockchain().tryToConnect(block);
	}

	/**
	 * 计算一个矿工所有未支付的数据 返回的是一个平均的 diff 对过去的十六个难度的平均值
	 */
	private static double processOutdatedMiner(Miner miner) {

		logger.debug("processOutdatedMiner");
		double sum = 0.0;
		int diffcount = 0;
		double temp;
		for (int i = 0; i < CONFIRMATIONS_COUNT; i++) {
			if ((temp = miner.getMaxDiffs(i)) > 0) {
				sum += temp;
				miner.setMaxDiffs(i, 0.0);
				++diffcount;
			}
		}

		if (diffcount > 0) {
			sum /= diffcount;
		}
		return sum;
	}

	/** 实现一个由难度转换为pay 的函数 */
	private static double diffToPay(double sum, int diffCount) {
		double result = 0.0;

		if (diffCount > 0) {
			result = Math.pow(E, ((sum / diffCount) - 20)) * diffCount;
		}
		return result;
	}

}
