#!/usr/bin/env python3

"""Export a marked SGF position as a new SGF with root AB/AW setup stones."""

import argparse
from pathlib import Path

from sgfmill import boards, sgf, sgf_moves


POSITION_PROPERTIES = {"AB", "AW", "AE", "B", "W", "PL"}
ROOT_PROPERTIES_TO_SKIP = POSITION_PROPERTIES | {"HA"}


def parse_args():
    parser = argparse.ArgumentParser(
        description=(
            "Find the node whose comment is exactly 'start', turn the board "
            "position at that node into root setup stones, and discard all "
            "moves and variations after that point."
        )
    )
    parser.add_argument("input_sgf", type=Path, help="Source SGF file")
    parser.add_argument("output_sgf", type=Path, help="Output SGF file")
    parser.add_argument(
        "--marker",
        default="start",
        help="Exact comment text used to mark the starting node (default: start)",
    )
    parser.add_argument(
        "--keep-marker-comment",
        action="store_true",
        help="Preserve the marker comment on the exported root node",
    )
    return parser.parse_args()


def format_path(path):
    if not path:
        return "root"
    return ".".join(str(index) for index in path)


def node_comment(node):
    if node.has_property("C"):
        return node.get("C")
    return None


def comment_has_marker(comment, marker):
    first_line, _, _ = comment.partition("\n")
    return first_line.strip() == marker


def comment_without_marker(comment, marker):
    if not comment_has_marker(comment, marker):
        return comment
    _, _, remainder = comment.partition("\n")
    cleaned = remainder.lstrip("\r\n")
    if cleaned:
        return cleaned
    return None


def find_marked_nodes(root, marker):
    matches = []
    stack = [(root, ())]
    while stack:
        node, path = stack.pop()
        comment = node_comment(node)
        if comment is not None and comment_has_marker(comment, marker):
            matches.append((path, node))
        for child_index in range(len(node) - 1, -1, -1):
            stack.append((node[child_index], path + (child_index,)))
    return matches


def path_nodes(root, path):
    nodes = [root]
    node = root
    for child_index in path:
        node = node[child_index]
        nodes.append(node)
    return nodes


def default_player(root):
    if root.has_property("PL"):
        return root.get("PL").lower()
    if root.has_property("HA"):
        try:
            if int(root.get("HA")) > 1:
                return "w"
        except ValueError:
            pass
    return "b"


def board_and_next_player(game, path):
    board = boards.Board(game.get_size())
    next_player = default_player(game.get_root())
    nodes = path_nodes(game.get_root(), path)
    for depth, node in enumerate(nodes):
        node_path = path[:depth]
        black_points, white_points, empty_points = node.get_setup_stones()
        if black_points or white_points or empty_points:
            legal = board.apply_setup(black_points, white_points, empty_points)
            if not legal:
                raise ValueError(
                    f"illegal setup stones at node {format_path(node_path)}"
                )
        if node.has_property("PL"):
            next_player = node.get("PL").lower()

        colour, move = node.get_move()
        if colour is None:
            continue
        if move is not None:
            row, col = move
            try:
                board.play(row, col, colour)
            except ValueError as exc:
                raise ValueError(
                    f"illegal move at node {format_path(node_path)}: {exc}"
                ) from exc
        next_player = "w" if colour == "b" else "b"
    return board, next_player


def copy_raw_properties(
    source_node,
    destination_node,
    marker,
    keep_marker_comment,
    skip_properties,
):
    comment = node_comment(source_node)
    for identifier, values in source_node.get_raw_property_map().items():
        if identifier in skip_properties:
            continue
        if identifier == "C":
            continue
        destination_node.set_raw_list(identifier, list(values))

    if comment is None:
        return
    if keep_marker_comment or not comment_has_marker(comment, marker):
        destination_node.set("C", comment)
        return

    cleaned_comment = comment_without_marker(comment, marker)
    if cleaned_comment is not None:
        destination_node.set("C", cleaned_comment)


def export_marked_position(input_path, output_path, marker, keep_marker_comment):
    with input_path.open("rb") as infile:
        game = sgf.Sgf_game.from_bytes(infile.read())

    matches = find_marked_nodes(game.get_root(), marker)
    if not matches:
        raise ValueError(f"no node found with comment {marker!r}")
    if len(matches) > 1:
        match_paths = ", ".join(format_path(path) for path, _ in matches)
        raise ValueError(
            f"found multiple nodes with comment {marker!r}: {match_paths}"
        )

    target_path, target_node = matches[0]
    board, next_player = board_and_next_player(game, target_path)

    new_game = sgf.Sgf_game(game.get_size(), encoding=game.get_charset())
    new_root = new_game.get_root()
    copy_raw_properties(
        game.get_root(),
        new_root,
        marker,
        keep_marker_comment,
        ROOT_PROPERTIES_TO_SKIP,
    )
    if target_node is not game.get_root():
        copy_raw_properties(
            target_node,
            new_root,
            marker,
            keep_marker_comment,
            POSITION_PROPERTIES,
        )

    sgf_moves.set_initial_position(new_game, board)
    new_root.set("PL", next_player)

    with output_path.open("wb") as outfile:
        outfile.write(new_game.serialise())


def main():
    args = parse_args()
    try:
        export_marked_position(
            args.input_sgf,
            args.output_sgf,
            marker=args.marker,
            keep_marker_comment=args.keep_marker_comment,
        )
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc


if __name__ == "__main__":
    main()
