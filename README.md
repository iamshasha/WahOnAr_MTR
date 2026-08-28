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
> **未來方向：**本伺服器倚賴的擴充模組已經搬到 MTR 4，而這個分支加的東西大部分原來上游已經有。
> 兩個版本都以相同的資料夾結構、用 MessagePack 檔案儲存世界，
> 因此兩者之間的轉換器由我們自己寫 —— **正反兩個方向都寫** ——
> 而且它們只是普通程式，執行和測試都不需要 Minecraft。
> 遷移的理由、期間如何令 MTR 3 保持穩定、萬一出事如何備份與還原世界、
> 以及不支援 MTR 4 的擴充模組和列車包會如何處理，全部寫在
> **[MIGRATION.zh-Hant.md](MIGRATION.zh-Hant.md)**。目前尚未有任何決定。
>
> **分支。**`main` 是 **MTR 3** 這條線 —— 伺服器執行的就是它，每一個發佈版本也都從它切出。
> `MTR-4-DEV` 是 MTR 4 工作所在的分支，並且有自己的更新紀錄
> （[CHANGELOG-MTR4.md](../../blob/MTR-4-DEV/CHANGELOG-MTR4.md)）。
> 在遷移真正拍板之前，它不會合併回來，上面的東西也不會出貨。

# Minecraft Transit Railway 3.0

_Minecraft Transit Railway_ is a [Minecraft mod](https://minecraft.gamepedia.com/Mods) based on Hong Kong's MTR, the London Underground, and the New York Subway. It adds trains into the game along with other miscellaneous blocks and items. With this mod, it is possible to build a fully functional railway system in your world!

[![Video Trailer](https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/video-preview.png)](https://www.youtube.com/watch?v=1cZfU7t4cAk)

Please report any issues or bugs that you find; that would be greatly appreciated! Refer to the [todo list](https://github.com/jonafanho/Minecraft-Transit-Railway/projects/2) to see currently known issues.

## Downloads and Installation

Head over to the [CurseForge page](https://www.curseforge.com/minecraft/mc-mods/minecraft-transit-railway) to download
the mod or to see the project information.

## Guide

There is a [new wiki](https://github.com/jonafanho/Minecraft-Transit-Railway/wiki) for the mod, right here on GitHub!
Take a look.

## Contributing

### Help Translate the Mod!

The [Crowdin site for the Minecraft Transit Railway mod](https://crwd.in/minecraft-transit-railway) is available!

Crowdin is a cloud-based platform for translators to contribute to a project. With your help, we can translate the mod to many different languages. You may create a free account to start translating.

[![Crowdin](https://badges.crowdin.net/minecraft-transit-railway/localized.svg)](https://crowdin.com/project/minecraft-transit-railway)

### Adding Features

1. Fork this project
1. On your fork, create a new branch based on the development version branch
1. Commit your changes to the new branch
1. Make a Pull Request to merge your branch into the development version of this repository

### Building

To build the mod, run the following command in the root directory of the project:

```
gradlew build -PbuildVersion=<minecraft version>
```

The mod jar file should be generated in the following directory:

```
<root>/build/release/MTR-<fabric|forge>-<minecraft version>-<mod version>.jar
```

## License

This project is licensed with the [MIT License](https://opensource.org/licenses/MIT). All [Noto fonts](http://www.google.com/get/noto/), bundled with this mod, are licensed with the [Open Font License](http://scripts.sil.org/OFL).

## Questions? Comments? Complaints?

Let's connect.

<a href="https://discord.gg/PVZ2nfUaTW" target="_blank"><img src="https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/discord.png" alt="Discord" width=64></a>
&nbsp;
<a href="https://www.linkedin.com/in/jonathanho33" target="_blank"><img src="https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/linked_in.png" alt="LinkedIn" width=64></a>
&nbsp;
<a href="mailto:jonho.minecraft@gmail.com" target="_blank"><img src="https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/email.png" alt="Email" width=64></a>
&nbsp;
<a href="https://www.patreon.com/minecraft_transit_railway" target="_blank"><img src="https://github.com/jonafanho/Minecraft-Transit-Railway/blob/master/images/footer/patreon.png" alt="Patreon" width=64></a>
