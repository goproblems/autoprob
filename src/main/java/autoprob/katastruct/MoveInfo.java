package autoprob.katastruct;

import java.text.DecimalFormat;
import java.util.List;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class MoveInfo {
	private static final DecimalFormat df = new DecimalFormat("0.00");

	@SerializedName("lcb")
	@Expose
	public Double lcb;
	@SerializedName("move")
	@Expose
	public String move;
	@SerializedName("order")
	@Expose
	public Integer order;
	@SerializedName("prior")
	@Expose
	public Double prior;
	@SerializedName("pv")
	@Expose
	public List<String> pv = null;
	@SerializedName("scoreLead")
	@Expose
	public Double scoreLead;
	@SerializedName("scoreMean")
	@Expose
	public Double scoreMean;
	@SerializedName("scoreSelfplay")
	@Expose
	public Double scoreSelfplay;
	@SerializedName("scoreStdev")
	@Expose
	public Double scoreStdev;
	@SerializedName("utility")
	@Expose
	public Double utility;
	@SerializedName("utilityLcb")
	@Expose
	public Double utilityLcb;
	@SerializedName("visits")
	@Expose
	public Integer visits;
	@SerializedName("winrate")
	@Expose
	public Double winrate;
	@SerializedName("weight")
	@Expose
	public Double weight;

	@Override
	public String toString() {
		return move;
	}

	public String extString() {
		return move + ", visits: " + visits + ", policy: " + (int)(prior * 1000.0);
	}
}