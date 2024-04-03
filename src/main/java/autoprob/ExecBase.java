package autoprob;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Properties;

/**
 * common logic for starting an executable, parsing config plus command line overrides
 *
 */
public class ExecBase {
	public static Properties getRunConfig(String[] args) throws Exception {
		if (args.length == 0) {
			throw new RuntimeException("first argument must be a path to the config file");
		}
		// load properties file
		String propPath = args[0];
		
		InputStream input = new FileInputStream(propPath);
		Properties prop = new Properties();
		prop.load(input);
		
		// load any additional properties from the command line
		// format is propertyname=value
		
		StringBuilder sb = new StringBuilder();
		for (int i = 1; i < args.length; i++) {
			String arg = args[i];
			if (!arg.contains("=")) {
				throw new RuntimeException("arguments after the config file override properties from the config file with format name=value");
			}
		    sb.append(arg).append("\n");
		}
		String commandlineProperties = sb.toString();
		if (!commandlineProperties.isEmpty()) {
		    // read, and overwrite, properties from the commandline...
		    prop.load(new StringReader(commandlineProperties));
		}
		
		return prop;
	}
}
