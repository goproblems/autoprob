package autoprob.scenario;

public class Analysis {
    /*
    sample result from API: http://localhost/api/v2/analysis-requests/next

    {
  "id": 1,
  "scenario": {
    "id": 1,
    "type": "invade",
    "elo": 1200,
    "sgf": "(;GM[1]FF[4]CA[UTF-8]AP[Drago:4.33]SZ[19]KM[9.5]AB[db][eb][nb][ob][hc][lc][qd][he][le][qe][ef][gf][if][pf][jg][lg][ch][eh][jh][kh][ph][pj][pk][ql][pm][qn][mo][oo][dp][gp][hp][ip][jp][np][op][dq][iq][kq][nq][pq][dr][or]AW[fb][hb][pb][cc][ec][fc][ic][jc][oc][qc][rc][dd][nd][pd][je][cf][jf][kf][lf][nf][kg][nh][nj][ok][ol][pl][mp][pp][qp][eq][gq][hq][jq][mq][er][ir][jr][kr][lr][nr][ms]PL[W])",
    "intro": "",
    "createdAt": "2025-11-01T22:38:26+00:00",
    "imageUrl": "/files/scenario-images/1.svg?thumbnail=1",
    "toMove": "w"
  },
  "path": "Fo",
  "difficulty": "ai",
  "requestedAt": "2025-11-01T22:38:26+00:00"
}
     */

    /*
    sample analysis result:

    \"results\": [
            {
              \"path\": \"Fo\",
              \"loss\": -0.15,
              \"score\": 2.3,
              \"urgency\": 0.8,
              \"endness\": -1,
              \"rank\": \"ai\",
              \"weight\": 0.9,
              \"katagoPlayouts\": 10000,
              \"katagoWeightsFile\": \"kata1-b40c256-s11840935168-d2898845681.bin.gz\",
              \"extraInfo\": \"Strong invasion move\"
            }
            ]
     */
}
