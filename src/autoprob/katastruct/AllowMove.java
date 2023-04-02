package autoprob.katastruct;

import java.util.List;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class AllowMove {
	
	@SerializedName("player")
	@Expose
	public String player;
	@SerializedName("untilDepth")
	@Expose
	public int untilDepth;
	@SerializedName("moves")
	@Expose
	public List<String> moves = null;

}