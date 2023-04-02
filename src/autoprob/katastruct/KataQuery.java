package autoprob.katastruct;

import java.util.List;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class KataQuery {

	@SerializedName("id")
	@Expose
	public String id;
	@SerializedName("initialStones")
	@Expose
	public List<List<String>> initialStones = null;
	@SerializedName("moves")
	@Expose
	public List<List<String>> moves = null;

	@SerializedName("rules")
	@Expose
	public String rules = "tromp-taylor";

	@SerializedName("initialPlayer")
	@Expose
	public String initialPlayer = "B";
	
	@SerializedName("komi")
	@Expose
	public Double komi;
	
	public Boolean includeOwnership;
	public Boolean includeOwnershipStdev;
	public Boolean includeMovesOwnership;
	public Boolean includePolicy;

	@SerializedName("maxVisits")
	@Expose
	public Integer maxVisits;

	@SerializedName("boardXSize")
	@Expose
	public Integer boardXSize = 19;

	@SerializedName("boardYSize")
	@Expose
	public Integer boardYSize = 19;
	
	@SerializedName("analyzeTurns")
	@Expose
	public List<Integer> analyzeTurns = null;

	@SerializedName("allowMoves")
	@Expose
	public List<AllowMove> allowMoves = null;
}