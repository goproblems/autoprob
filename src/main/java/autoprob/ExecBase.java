package autoprob;

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Properties;

/**
 * Parses an optional properties file followed by command-line name=value settings.
 *
 */
public class ExecBase {
	public static Properties getRunConfig(String[] args) throws Exception {
		if (args.length == 0) {
			throw new RuntimeException("pass a config file or command-line properties such as cmd=josekinodecomplete");
		}
		Properties prop = new Properties();
		int firstOverride = 0;
		if (!args[0].contains("=")) {
			try (InputStream input = new FileInputStream(args[0])) {
				prop.load(input);
			}
			firstOverride = 1;
		}
		
		// load any additional properties from the command line
		// format is propertyname=value
		
		StringBuilder sb = new StringBuilder();
		for (int i = firstOverride; i < args.length; i++) {
			String arg = args[i];
			if (!arg.contains("=")) {
				throw new RuntimeException("command-line properties must use the format name=value");
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
