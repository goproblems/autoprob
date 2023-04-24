package autoprob;

import java.util.Properties;

// run detector with visualization
public class VisRunner {

	public static void main(String[] args) throws Exception {
		System.out.println("vis runner start...");

		Properties prop = ExecBase.getRunConfig(args);

		KataRunner kr = new KataRunner(prop);
		var vd = new VisDetector(prop);
		try {
			kr.startEngine(vd);
		} catch (Exception e) {
			e.printStackTrace();
//			System.exit(-1);
		}
	}
}
