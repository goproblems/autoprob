# autoprob
Automatically find and extract go problems from games. The resulting problems are formatted for adding to www.goproblems.com or potentially other resources.

This tool heavily uses katago for multiple stages of analysis.

# quickstart

1) download katago: https://github.com/lightvector/KataGo
2) download some weights. i recommend the b15 network for speed: https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b15c192-s1672170752-d466197061.txt.gz
3) make sure you have Java installed. compile the autoprob source or run existing jars (when available)
4) download a gson jar: https://github.com/google/gson
5) Validate it works with a sample test file. Run the VisRunner main class. A simple command line might be:

`path_to\config.properties katago=path_to\katago.exe kata.config=path_to\analysis_example.cfg kata.model=path_to\weights.txt.gz path=sample_games\2019-04-01-123_m198.sgf turn=198 forceproblem=true`

you will have to pass in the gson jar. this should pop up a Java window with a detected problem from this game. replace the paths with paths to your katago executable, config file, and weights.

if you are having problems, try passing `kata.debug_print=true` on the command line also

6) Edit the board on the right, which is the problem board, to fix any extraction mistakes. Left click for black, right click for white.
7) Hit the Make Paths button to generate solution and refutation paths with katago.

# Configuration

The general approach is to take configuration parameters from the properties file, optionally overridden by using the same names on the command line (with the format `name=value`). The first command line parameter must be a path to the properties file, and this is the only unnamed parameter. A default file is provided. There are a lot of options!

# Developer IDE

This has been loaded in both IntelliJ and Eclipse. Some project files may exist and work.

# Scanning SGF directories

This is a key mode of using autoprob. Given a directory full of SGF files, this will iterate through them, looking in all games to find potential problems. For each suggested problem, autoprob will pop up a window showing what it has found and giving the user the option to modify the problem and generate paths from it.

To run in this mode, set `path` on the command line or config file to point to the directory to scan. (Note: it's easy to find large collections of amateur games played on go servers.)

By default, autoprob will save its progress in the directory in a file called `zpos`, so you can run it again later on the same directory for new files.

The program will continue finding potential problems until `search.directory.max_finds` is reached.

Lots of configuration options can control the search -- look in the config.properties file for a complete list. There's a general tradeoff between accuracy and speed, represented by the number of visits used for each position in the tree. There's another high level tradeoff between finding more problems, and trying to find better problems. For example, the configuration parameter `search.max_policy=0.6` is a way of estimating how obvious a solution in. This corresponds to katago's policy function (how likely it believes a given move is without search.) Setting this value higher allows more potential go problems to be found, but they may be less interesting.

As directory scanning is running, autoprob will keep going through SGF files looking for potential go problems. Each one will pop up into a new window, ready for next steps. As of this writing, only about 10% of these are actually good problem candidates. It takes some practice to quickly judge this. Hopefully this percentage will increase in the future.

# Problem extraction

This is the step where a game position looks like a good candidate go problem, and autoprob tries to extract just the relevant parts into the board on the right. This almost never goes perfectly, and thus requires a little manual editing. This is generally super quick. Fixing outside holes is often a good idea so you don't get problem "leaks". Another common issue is extra stones in random places on the board.

Editing commands:
Problem board:
left click add/remove black stone
right click add/remove white stone
control-left click to remove a group
Game board:
left click to move a connected group to problem board




<img width="1343" alt="image" src="https://github.com/adum/autoprob/assets/52733/ba11b01f-218c-4c55-9860-ac99dec72c0a">

