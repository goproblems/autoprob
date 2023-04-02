package autoprob.test;

import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JButton;
import javax.swing.JFrame;

import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.go.vis.BasicGoban;
import autoprob.go.vis.BasicGoban2D;
import autoprob.go.vis.Pix;

public class ShowGoban {

	public static void main(String[] args) throws Exception {
		JFrame f=new JFrame();//creating instance of JFrame  
        
		JButton b=new JButton("click");//creating instance of JButton  
		b.setBounds(130,100,100, 40);//x axis, y axis, width, height  
		          
		Pix.LoadAll();
		
		String sgfPath = "C:\\Users\\Adam\\Documents\\problems\\230_12_k.sgf";
		String sgf = Files.readString(Path.of(sgfPath));
		var parser = new Parser();
		Node node = parser.parse(sgf);

//		f.add(b);//adding button in JFrame  
//		Node node = new Node(null);
		BasicGoban goban = new BasicGoban2D(node, null);
		goban.setBounds(0, 0, 800, 800);
		f.add(goban);
		          
		f.setSize(400,500);//400 width and 500 height  
		f.setLayout(null);//using no layout managers  
		f.setVisible(true);//making the frame visible  
		
		goban.goLarge();
	}

}
