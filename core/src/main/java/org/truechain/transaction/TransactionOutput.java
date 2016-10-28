package org.truechain.transaction;

import java.io.IOException;
import java.io.OutputStream;

import org.truechain.account.RedeemData;
import org.truechain.address.Address;
import org.truechain.core.Coin;
import org.truechain.core.VarInt;
import org.truechain.core.exception.ScriptException;
import org.truechain.crypto.ECKey;
import org.truechain.script.Script;
import org.truechain.script.ScriptBuilder;
import org.truechain.utils.Utils;

/**
 * 交易输出，本次的输出是下次花费时的输入
 * @author ln
 *
 */
public class TransactionOutput {

	private Transaction parent;
	//下次的花费
	private TransactionInput spentBy;
	
	private long value;

    private byte[] scriptBytes;
    
    private Script script;
    //交易输出的索引
    private int index;
    
    public TransactionOutput() {
    	
    }
	
	public TransactionOutput(Transaction parent, Coin value, Address to) {
		this(parent, value, ScriptBuilder.createOutputScript(to).getProgram());
	}
	public TransactionOutput(Transaction parent, Coin value, ECKey to) {
		this(parent, value, ScriptBuilder.createOutputScript(to).getProgram());
	}
	public TransactionOutput(Transaction parent, Coin value, byte[] scriptBytes) {
		this.parent = parent;
		this.value = value.value;
        this.scriptBytes = scriptBytes;
        this.script = new Script(scriptBytes);
	}
	
	/**
	 * 序列化
	 * @param stream
	 * @throws IOException 
	 */
	public void serialize(OutputStream stream) throws IOException {
		Utils.int64ToByteStreamLE(value, stream);
		stream.write(new VarInt(scriptBytes.length).encode());
		stream.write(scriptBytes);
	}

	/**
	 * 获取交易的输出脚本
	 * @param key
	 * @return
	 * @throws ScriptException
	 */
	public RedeemData getRedeemData(ECKey key) throws ScriptException {
		if (script.isSentToAddress()) {
	        return RedeemData.of(key, script);
	    } else {
            throw new ScriptException("Could not understand form of connected output script: " + script);
        }
	}
	
	public Transaction getParent() {
		return parent;
	}
	public void setParent(Transaction parent) {
		this.parent = parent;
	}
	public TransactionInput getSpentBy() {
		return spentBy;
	}
	public void setSpentBy(TransactionInput spentBy) {
		this.spentBy = spentBy;
	}
	public Script getScript() {
		return script;
	}
	public void setScript(Script script) {
		this.script = script;
		this.scriptBytes = script.getProgram();
	}
	public byte[] getScriptBytes() {
		return scriptBytes;
	}
	public void setScriptBytes(byte[] scriptBytes) {
		this.scriptBytes = scriptBytes;
		this.script = new Script(scriptBytes);
	}
	public int getIndex() {
		return index;
	}
	public void setIndex(int index) {
		this.index = index;
	}
	public long getValue() {
		return value;
	}
	public void setValue(long value) {
		this.value = value;
	}
	
}
