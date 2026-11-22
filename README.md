# DeathBan (Paper 26.3)

Bans a player when they die, with a lightning strike and a Warden sound. Admins can pardon.

## Build with GitHub (no install needed)

1. Create a GitHub repo and upload everything in this folder.
   - `.github` is a hidden folder. On Mac press Cmd+Shift+. in Finder to show it, then drag it in.
   - If it still won't upload: in the repo click Add file > Create new file, type `.github/workflows/build.yml`
     as the name and paste in the contents of `.github/workflows/build.yml` from this zip.
2. Open the Actions tab. The build starts by itself (or click Run workflow).
3. When it shows a green check, open the run and download **DeathBan** under Artifacts.
4. Unzip it and put only `DeathBan.jar` in your server's `plugins` folder, then restart.

Needs Paper 26.3+ and Java 25.

## Commands
- `/deathpardon <player>` (alias `/dpardon`): let a banned player play again
- `/deathbans list | check <player> | reload` (alias `/dbans`)

Permissions: `deathban.pardon`, `deathban.admin` (op by default), `deathban.bypass` (never banned on death).
Settings (ban length, sound, lightning, messages) are in `plugins/DeathBan/config.yml`.
