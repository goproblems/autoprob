package autoprob.go.parse;

import autoprob.go.Node;

// looks for longest comment -- we use this to calculate the size of needed comment area at top


public class LongCommentRecurser extends NodeRecurser {
	 public String longest = "";

  public LongCommentRecurser() {
  }

  public void action(Node n) {
		if ( (n.comment != null) && (n.comment.length() > longest.length()) )
			 longest = n.comment;
  }

}
