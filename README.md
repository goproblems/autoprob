# autoprob
automatically find and extract go problems from games
more documentation coming!

# quickstart

1) download katago: https://github.com/lightvector/KataGo
2) download some weights. i recommend the b15 network for speed: https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b15c192-s1672170752-d466197061.txt.gz
3) make sure you have Java installed, compile or run existing jars (when available)
4) validate it works with a sample test file. a simple command line might be:
path_to\config.properties path_to\katago.exe kata.config=path_to\analysis_example.cfg kata.model=path_to\weights.txt.gz path=sample_games\\2019-04-01-123_m198.sgf turn=198 forceproblem=true
this should pop up a Java window with a detected problem from this game
