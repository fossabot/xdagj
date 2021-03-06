/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.net.handler;

import com.google.common.util.concurrent.ListenableFuture;
import io.xdag.core.Block;
import io.xdag.net.XdagVersion;
import io.xdag.net.message.Message;
import io.xdag.net.message.impl.SumReplyMessage;
import java.math.BigInteger;

public class XdagAdapter implements Xdag {
    @Override
    public void sendNewBlock(Block newBlock, int TTl) {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendGetblocks(long starttime, long endtime) {
        // TODO Auto-generated method stub
    }

    @Override
    public void sendGetblock(byte[] hash) {
        // TODO Auto-generated method stub

    }

    @Override
    public ListenableFuture<SumReplyMessage> sendGetsums(long starttime, long endtime) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void dropConnection() {
        // TODO Auto-generated method stub

    }

    @Override
    public boolean isIdle() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public BigInteger getTotalDifficulty() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void activate() {
        // TODO Auto-generated method stub

    }

    @Override
    public XdagVersion getVersion() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void disableBlocks() {
        // TODO Auto-generated method stub

    }

    @Override
    public void enableBlocks() {
        // TODO Auto-generated method stub

    }

    @Override
    public void onSyncDone(boolean done) {
        // TODO Auto-generated method stub

    }

    @Override
    public void sendMessage(Message message) {
    }
}
