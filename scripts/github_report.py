#!/usr/bin/env python3
"""Report Forgejo CI results back to the GitHub mirror, so GitHub shows what Forgejo verified.

Forgejo is the source of truth and GitHub only mirrors it (see forgejo-ci.yml), but GitHub is
where people look. This posts, onto the SAME commit on GitHub:

  status      a commit status (e.g. context "forgejo/compat-gate") that appears next to
              GitHub's own checks on the commit and on any PR containing it
  deployment  a GitHub deployment to an environment (e.g. "gmc101"), so the repo's
              Environments sidebar shows which commit is live on the server

It NEVER fails the calling job: reporting is a courtesy, and a GitHub outage or a commit that
has not been mirrored yet must not block a build or, worse, a deploy. Errors are printed as
warnings and the exit code is always 0. A commit GitHub doesn't know yet (HTTP 422) is retried
a few times, because the mirror push may land a moment later.

Needs GH_TOKEN with "Commit statuses: write" and "Deployments: write" on k33bz/sanctuary.
Standard library only.

  github_report.py status --sha SHA --state success|failure|error|pending \
      --context forgejo/compat-gate --description "..." [--url RUN_URL]
  github_report.py deployment --sha SHA --environment gmc101 --state success|failure \
      [--description "..."] [--url RUN_URL]
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

REPO = os.environ.get("GITHUB_MIRROR_REPO", "k33bz/sanctuary")
API = "https://api.github.com"


def call(method, path, body, token):
    req = urllib.request.Request(
        API + path, data=json.dumps(body).encode(), method=method,
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                 "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "sanctuary-forgejo-ci"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def with_retry(fn, attempts=5, wait=30):
    """Retry while GitHub doesn't know the commit yet (422): the mirror push may be in flight."""
    for i in range(attempts):
        try:
            return fn()
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            if e.code == 422 and i < attempts - 1:
                print(f"GitHub does not have the commit yet (422), retrying in {wait}s", flush=True)
                time.sleep(wait)
                continue
            print(f"::warning::GitHub report failed: HTTP {e.code} {detail}")
            return None
        except Exception as e:  # network, DNS, timeouts: never fail the job
            print(f"::warning::GitHub report failed: {e}")
            return None
    return None


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    st = sub.add_parser("status")
    st.add_argument("--sha", required=True)
    st.add_argument("--state", required=True, choices=["success", "failure", "error", "pending"])
    st.add_argument("--context", required=True)
    st.add_argument("--description", default="")
    st.add_argument("--url")
    st.add_argument("--attempts", type=int, default=5)
    dp = sub.add_parser("deployment")
    dp.add_argument("--sha", required=True)
    dp.add_argument("--environment", required=True)
    dp.add_argument("--state", required=True, choices=["success", "failure", "error"])
    dp.add_argument("--description", default="")
    dp.add_argument("--url")
    a = ap.parse_args()

    token = os.environ.get("GH_TOKEN", "")
    if not token:
        print("::warning::GH_TOKEN not set; skipping the GitHub report")
        return 0

    if a.cmd == "status":
        body = {"state": a.state, "context": a.context, "description": a.description[:140]}
        if a.url:
            body["target_url"] = a.url
        if with_retry(lambda: call("POST", f"/repos/{REPO}/statuses/{a.sha}", body, token), attempts=a.attempts):
            print(f"reported {a.context}={a.state} on {a.sha[:10]}")
        return 0

    dep = with_retry(lambda: call("POST", f"/repos/{REPO}/deployments", {
        "ref": a.sha, "environment": a.environment, "auto_merge": False,
        "required_contexts": [], "description": a.description[:140],
        "production_environment": True}, token))
    if not dep or "id" not in dep:
        return 0
    body = {"state": a.state, "description": a.description[:140], "auto_inactive": True}
    if a.url:
        body["log_url"] = a.url
    if with_retry(lambda: call("POST", f"/repos/{REPO}/deployments/{dep['id']}/statuses", body, token)):
        print(f"recorded deployment of {a.sha[:10]} to {a.environment}: {a.state}")
    return 0


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # belt and braces: reporting never breaks the pipeline
        print(f"::warning::GitHub report crashed: {e}")
    sys.exit(0)
