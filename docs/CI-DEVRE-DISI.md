docs: note that the workflows are disabled while runners are unavailable

All three workflows are disabled_manually. Pushing while they are enabled
creates a failing run, and every failing run notifies the repository owner, so
a routine commit during an outage costs a person an email. Re-enable with
PUT /repos/{owner}/{repo}/actions/workflows/{id}/enable once runners return.
