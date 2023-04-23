package autoprob;

import java.util.Properties;

public class Extractor {
	public static void main(String[] args) throws Exception {
		Properties prop = ExecBase.getRunConfig(args);
		
		KataRunner kr = new KataRunner(prop);
		kr.startEngine(null);
	}
}
