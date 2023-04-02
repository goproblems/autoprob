package autoprob.katastruct;

import java.util.List;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class RootInfo {

	@SerializedName("currentPlayer")
	@Expose
	public String currentPlayer;
	@SerializedName("scoreLead")
	@Expose
	public Double scoreLead;
	@SerializedName("scoreSelfplay")
	@Expose
	public Double scoreSelfplay;
	@SerializedName("scoreStdev")
	@Expose
	public Double scoreStdev;
	@SerializedName("symHash")
	@Expose
	public String symHash;
	@SerializedName("thisHash")
	@Expose
	public String thisHash;
	@SerializedName("utility")
	@Expose
	public Double utility;
	@SerializedName("visits")
	@Expose
	public Integer visits;
	@SerializedName("winrate")
	@Expose
	public Double winrate;
}