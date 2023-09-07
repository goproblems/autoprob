package autoprob.go;

import java.awt.Point;
import java.util.ArrayList;

import autoprob.go.Board;

/**
 * various logic for calculating connectivity between stones
 */
public class StoneConnect {
	int[][] offsets = {{-1, 0}, {0, 1}, {1, 0}, {0, -1}};

	public ArrayList<Point> calcNeighbors(Point p, Board board) {
		ArrayList<Point> res = new ArrayList<Point>();
		int x = p.x, y = p.y;
		addStone(res, p.x + 1, p.y);
		addStone(res, p.x - 1, p.y);
		addStone(res, p.x, p.y + 1);
		addStone(res, p.x, p.y - 1);
		// include empty diagonals
		// x.
		// .x
		if (isOnboard(x - 1, y - 1))
			if (board.board[x - 1][y].stone == 0 && board.board[x][y - 1].stone == 0)
				addStone(res, x - 1, y - 1);
		return res;
	}

	public ArrayList<Point> emptyNeighbors(Point p, Board board) {
		ArrayList<Point> res = new ArrayList<Point>();
		int x = p.x, y = p.y;
		addStoneIf(res, p.x + 1, p.y, 0, board);
		addStoneIf(res, p.x - 1, p.y, 0, board);
		addStoneIf(res, p.x, p.y + 1, 0, board);
		addStoneIf(res, p.x, p.y - 1, 0, board);
		return res;
	}
	
	// add points to list that are connected, same stone as input
	public ArrayList<Point> calcConnected(Point p, Board board) {
		ArrayList<Point> res = new ArrayList<Point>();
		int x = p.x, y = p.y;
		int stn = board.board[p.x][p.y].stone;
		if (stn == 0) return res;
		addStoneIf(res, p.x + 1, p.y, stn, board);
		addStoneIf(res, p.x - 1, p.y, stn, board);
		addStoneIf(res, p.x, p.y + 1, stn, board);
		addStoneIf(res, p.x, p.y - 1, stn, board);
		return res;
	}
	
	private void addStone(ArrayList<Point> res, int x, int y) {
		if (!isOnboard(x, y)) return;
		res.add(new Point(x, y));
	}

	public boolean isOnboard(int x, int y) {
		return !(x < 0 || y < 0 || x >= 19 || y >= 19);
	}

	private void addStoneIf(ArrayList<Point> res, int x, int y, int stn, Board board) {
		if (!isOnboard(x, y)) return;
		if (board.board[x][y].stone != stn)
			return;
		res.add(new Point(x, y));
	}

	public ArrayList<Point> floodGroup(ArrayList<Point> starts, ArrayList<Point> domain,
			Board board) {
		ArrayList<Point> res = new ArrayList<Point>();
		boolean[][] fill = new boolean[19][19];
		boolean[][] match = new boolean[19][19];
		for (Point p: domain) {
			match[p.x][p.y] = true;
		}
		for (Point p: starts) {
			floodRecurse(p.x, p.y, match, board, fill, res);
		}
		return res;
	}

	public ArrayList<Point> floodFromStone(Point start, Board board) {
		ArrayList<Point> res = new ArrayList<Point>();
		boolean[][] fill = new boolean[19][19];
		boolean[][] match = new boolean[19][19];
		int stn = board.board[start.x][start.y].stone;
		for (int x = 0; x < 19; x++)
			for (int y = 0; y < 19; y++)
				match[x][y] = board.board[x][y].stone == stn;
		floodRecurse(start.x, start.y, match, board, fill, res);
		return res;
	}

	private void floodRecurse(int x, int y, boolean[][] match, Board board, boolean[][] fill, ArrayList<Point> res) {
		if (fill[x][y]) return;
		fill[x][y] = true;
		res.add(new Point(x, y));
		
		for (int i = 0; i < 4; i++) {
			int nx = x + offsets[i][0];
			int ny = y + offsets[i][1];
			if (!isOnboard(nx, ny)) continue;
			if (!match[nx][ny]) continue;
			floodRecurse(nx, ny, match, board, fill, res);
		}
	}
}
