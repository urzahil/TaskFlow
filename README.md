# TaskFlow

A task manager for Android built around your day. See what's due today, plan ahead on a monthly calendar, and set tasks to repeat on a schedule.

## Features

- **Three ways to look at your tasks**
  - **Daily view** for what's due today.
  - **Monthly calendar** for planning ahead.
  - **List view** of upcoming tasks grouped by day.
- **Search and filter** tasks, including by state.
- **Recurring tasks** that repeat on chosen weekdays or every N days, with an optional end date.
- **Custom categories** to organise your tasks.
- **Automatic rollover**
  - Unfinished one-time tasks from past days move to today.
  - Completed one-time tasks and expired recurring tasks are cleared.
- **Backup and restore** to a JSON file or to Google Drive, with optional automatic backup.

## Google Drive backup

Backing up to Google Drive needs Google sign-in, which depends on the app's signing key being registered with a Google Cloud OAuth client. If you build and sign the app yourself, set up your own OAuth client for your package name and signing certificate. Backing up to a JSON file works without this.

## License

Released under the [GNU General Public License v3.0](LICENSE).
