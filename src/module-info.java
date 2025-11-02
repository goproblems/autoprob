module autoprob {
	requires gson;
	requires java.sql;
	requires java.desktop;
	requires org.junit.jupiter.api;
	requires org.junit.platform.launcher;
	exports autoprob;
	exports autoprob.katastruct;
    exports autoprob.test;
	exports autoprob.go;
}
