"""Edit-format test for pipeline stage 4 (docs/plan/pipeline-fix-proposal).

Real fix commits whose change sits inside one method: give the model the method before the fix
(with line numbers, as the analyzer does) and the commit message, ask once for a unified diff and
once for SEARCH/REPLACE blocks, and check which ones apply to the real file. 2026-11-27, Sonnet 5.5,
18 usable cases: applied udiff 16 / search 18, right place 13 / 16.

Run from the repo root (needs git history); the model call runs in the analyzer container (needs the key):
    uv run --project alert-analyzer python alert-analyzer/eval/fix_format.py build /tmp/ds.json
    ssh vps 'docker exec -i observability-alert-analyzer-1 python -m scripts.fix_format_gen' < /tmp/ds.json > /tmp/ans.json
    python3 alert-analyzer/eval/fix_format.py evaluate /tmp/ds.json /tmp/ans.json /tmp/rows.json
"""

import sys


def build(out_path: str) -> None:
    
    import json, os, re, subprocess, sys
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
    from analyzer.code import method_at
    def git(*a): return subprocess.run(["git", *a], capture_output=True, text=True).stdout
    commits = git("log", "--no-merges", "--format=%H%x09%s", "origin/main", "-n", "4000").splitlines()
    out = []
    for line in commits:
        sha, subj = line.split("\t", 1)
        if not subj.startswith("fix("): continue
        files = [l.split("\t") for l in git("diff", "--numstat", f"{sha}^", sha).splitlines()]
        main = [f for f in files if len(f) == 3 and f[2].startswith("src/main/java/") and f[2].endswith(".java")
                and f[0].isdigit() and 0 < int(f[0]) + int(f[1]) <= 30]
        if not main: continue
        add, dele, path = main[0]
        pre = git("show", f"{sha}^:{path}")
        if not pre: continue
        diff = git("diff", "-U0", f"{sha}^", sha, "--", path)
        olds = []
        for m in re.finditer(r"^@@ -(\d+)(?:,(\d+))? \+", diff, re.M):
            start, count = int(m.group(1)), int(m.group(2) or 1)
            olds.append((start, max(count, 1)))
        spans = set()
        for start, count in olds:
            found = method_at(pre, max(start, 1))
            spans.add(found[:2] if found else None)
        if len(spans) != 1 or None in spans: continue
        s, e = spans.pop()
        if e - s + 1 > 150: continue
        lines = pre.splitlines()
        snippet = "\n".join(f"{n:>5} {lines[n-1]}" for n in range(s, e + 1))
        msg = git("log", "-1", "--format=%B", sha).strip()
        msg = "\n".join(l for l in msg.splitlines() if not l.startswith("Co-Authored-By"))
        out.append({"sha": sha, "path": path, "start": s, "end": e, "snippet": snippet, "message": msg,
                    "real_diff": git("diff", "-U3", f"{sha}^", sha, "--", path), "old_ranges": olds})
        if len(out) >= 20: break
    json.dump(out, open(out_path, "w"), ensure_ascii=False)
    print(len(out), [ (o["sha"][:8], o["path"].rsplit("/",1)[-1], o["end"]-o["start"]+1) for o in out])
    


def evaluate(ds_path: str, ans_path: str, rows_path: str) -> None:
    
    import json, re, subprocess, sys, tempfile, os, difflib
    ds = json.load(open(ds_path)); ans = json.load(open(ans_path))["answers"]
    def git(*a, cwd=None, inp=None):
        return subprocess.run(["git", *a], capture_output=True, text=True, cwd=cwd, input=inp)
    def changed_old_lines(pre, post):
        a, b = pre.splitlines(), post.splitlines()
        out = set()
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
            if tag != "equal":
                out.update(range(i1 + 1, max(i2, i1 + 1) + 1))
        return out
    def near(changed, ranges):
        real = set()
        for s, c in ranges: real.update(range(s - 3, s + c + 3))
        return bool(changed) and changed <= real
    rows = []
    for a in ans:
        d = ds[a["i"]]
        pre = git("show", f"{d['sha']}^:{d['path']}").stdout
        row = {"i": a["i"], "file": d["path"].rsplit("/", 1)[-1]}
        # unified diff
        with tempfile.TemporaryDirectory() as tmp:
            git("init", "-q", cwd=tmp)
            p = os.path.join(tmp, d["path"]); os.makedirs(os.path.dirname(p), exist_ok=True)
            open(p, "w").write(pre)
            m = re.search(r"```diff\n(.*?)```", a["udiff"], re.S)
            patch = (m.group(1) if m else a["udiff"])
            if not patch.endswith("\n"): patch += "\n"
            r = git("apply", "--check", "-", cwd=tmp, inp=patch)
            row["udiff_ok"] = r.returncode == 0
            row["udiff_err"] = r.stderr.strip().splitlines()[0][:90] if r.returncode else ""
            if r.returncode:
                row["udiff_ok_recount"] = git("apply", "--check", "--recount", "-", cwd=tmp, inp=patch).returncode == 0
            else:
                git("apply", "-", cwd=tmp, inp=patch)
                row["udiff_place"] = near(changed_old_lines(pre, open(p).read()), d["old_ranges"])
                row["udiff_post"] = open(p).read()
        # search/replace
        blocks = re.findall(r"<<<<<<< SEARCH\n(.*?)\n=======\n(.*?)\n?>>>>>>> REPLACE", a["search"], re.S)
        post, ok, err = pre, bool(blocks), "" if blocks else "no blocks"
        for s, rpl in blocks:
            n = post.count(s)
            if n != 1:
                ok = False
                err = f"found {n}x" + (" (line-number prefix)" if re.match(r"\s*\d+ ", s) else "")
                break
            post = post.replace(s, rpl, 1)
        row["search_ok"], row["search_err"], row["blocks"] = ok, err, len(blocks)
        if ok:
            row["search_place"] = near(changed_old_lines(pre, post), d["old_ranges"])
            row["search_post"] = post
        rows.append(row)
    json.dump(rows, open(rows_path, "w"), ensure_ascii=False)
    for r in rows:
        print(r["i"], r["file"][:34].ljust(34), "U", r["udiff_ok"], r.get("udiff_place"), r.get("udiff_ok_recount", ""), r["udiff_err"][:60], "| S", r["search_ok"], r.get("search_place"), r["search_err"])
    n = len(rows)
    print("udiff apply", sum(r["udiff_ok"] for r in rows), "/", n, " recount-rescue", sum(1 for r in rows if r.get("udiff_ok_recount")), " right place", sum(1 for r in rows if r.get("udiff_place")))
    print("search apply", sum(r["search_ok"] for r in rows), "/", n, " right place", sum(1 for r in rows if r.get("search_place")))
    


if __name__ == "__main__":
    command, *args = sys.argv[1:]
    {"build": build, "evaluate": evaluate}[command](*args)
