package autoprob.go.parse;
// looks for possible choice in computer's response

import autoprob.go.Node;

public class ChoiceRecurser extends NodeRecurser {
  public boolean choice = false;

  public ChoiceRecurser() {
  }

  public void action(Node n) {
    if (n.isChoice)
      choice = true;
  }

}
