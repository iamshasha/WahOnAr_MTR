> ### This is a modified build, not the original
>
> This repository is a **fork** of [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway)
> by Jonathan Ho, modified for the **Wah On Ar** Minecraft server. It is not the original mod, it is not
> maintained by the original author, and it is not endorsed by or affiliated with the original project.
> Changelog: [CHANGELOG.md](CHANGELOG.md) · [更新紀錄](CHANGELOG.zh-Hant.md)
>
> Builds here target **Fabric 1.19.4 only** and are distributed privately. For the real thing, go upstream.
>
> **Where this is going:** the addons this server depends on have moved to MTR 4, and most of what this fork
> adds turns out to already exist upstream. Both versions store a world as MessagePack files in the same folder
> layout, so the converters between them are ours to write — in **both** directions, forward and back — as plain
> programs that need no Minecraft to run or to test. The case for migrating, what is being done so that MTR 3
> stays stable in the meantime, how the world is backed up and reverted if it goes wrong, and what happens to
> addons and train packs that do not follow — all of it is written down in
> **[MIGRATION.md](MIGRATION.md)**. Nothing has been decided.
>
> **Branches.** `main` is the **MTR 3** line — it is what the server runs and what every release is cut
> from. `MTR-4-DEV` is where the MTR 4 work happens, with its own changelog
> ([CHANGELOG-MTR4.md](../../blob/MTR-4-DEV/CHANGELOG-MTR4.md)). It is not merged back, and nothing on it
> ships, until the migration is actually agreed.
>
> ### 這是修改版本，並非原版
>
> 本倉庫是 [Minecraft Transit Railway](https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway)（作者 Jonathan Ho）
> 的**分支**，為 **華安亞** Minecraft 伺服器而修改。這並非原版模組，並非由原作者維護，
> 亦與原專案沒有任何從屬或背書關係。此處的建置**只針對 Fabric 1.19.4**，並以私人方式分發。
> 想要原版請前往上游倉庫。
>
> **未來方向**
> MTR 3分支會繼續更新，而不會完全斷更。如發現任何問題，請至GitHub Issue提交問題。

# Minecraft Transit Railway 3.0

_Minecraft Transit Railway_ is a [Minecraft mod](https://minecraft.gamepedia.com/Mods) based on Hong Kong's MTR, the London Underground, and the New York Subway. It adds trains into the game along with other miscellaneous blocks and items. With this mod, it is possible to build a fully functional railway system in your world!

[![Video Trailer](https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/video-preview.png)](https://www.youtube.com/watch?v=1cZfU7t4cAk)

Please report any issues or bugs that you find; that would be greatly appreciated! Refer to the [todo list](https://github.com/jonafanho/Minecraft-Transit-Railway/projects/2) to see currently known issues.

## License

This project is licensed with the [MIT License](https://opensource.org/licenses/MIT). All [Noto fonts](http://www.google.com/get/noto/), bundled with this mod, are licensed with the [Open Font License](http://scripts.sil.org/OFL).