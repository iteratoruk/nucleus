# Contributing to Nucleus

Thank you for taking the time to contribute. This project welcomes pull requests from everyone. The sections below outline the basic workflow.

## Commit Messages

All commits must follow the [Conventional Commits](https://www.conventionalcommits.org/) specification. A few examples are:

```
feat: add customer onboarding API
fix: handle null pointer in account service
```

These messages are used to generate changelogs and help reviewers understand the purpose of each change.

## Running Tests and Checks

Before submitting a pull request, make sure the full test suite and static checks pass:

```bash
./gradlew build
```

## Pull Requests

1. Fork the repository and create a new branch for your changes.
2. Open a pull request with a clear description of what you have changed.
3. Reference any related issues in the pull request description using `Fixes #<issue number>` or `Closes #<issue number>`.
4. Ensure your branch is rebased on the latest `main` before you request a review.

We appreciate your contributions and will do our best to review them quickly.
