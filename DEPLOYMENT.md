Deployment to bedebog.dk
========================
The following is a short guide for people who want to contribute new data to
https://bedebog.dk. It assumes basic familiarity with the UNIX command line.

This is the **TL;DR** for how to deploy new TEI files:

```shell
ssh rqf595@bedebog.dk
git -C /opt/Data pull
/opt/copy-files.sh
systemctl restart prayer
```

Read on for a more in-depth explanation.

## Log on
The website is live on a KU server and is served from the domain `bedebog.dk`.
Provided that you have the correct permissions, you should be able to log on via
`ssh`, e.g. in my case I would use this command to log on with my KU user:

```shell
ssh rqf595@bedebog.dk
```

> NOTE: you need to be on KU's network for the SSH access to the server,
> i.e. either on a wired connection or via the KU VPN (Cisco Secure Client).
> If you can't log via SSH over the KU VPN, you need to ask KU-IT for help.

## Add new data
The TEI files must all go somewhere into the `/opt/tei-files` directory.
Whenever the system is (re)started it will look inside this directory for 
compatible, valid TEI files to build a new database from.
This database is what populates the various pages of the website.

The [bedebok/Data](https://github.com/bedebok/Data) repository is available at
`/opt/Data`. To update the database data, run `git pull` and transfer the files:

```shell
git -C /opt/Data pull

yes | cp /opt/Data/Texts/xml/*.xml /opt/tei-files
yes | cp /opt/Data/Works/xml/*.xml /opt/tei-files
yes | cp /opt/Data/Manuscripts/xml/*.xml /opt/tei-files
yes | cp /opt/Data/Gold\ corpus/*.xml /opt/tei-files
```

I also made a script containing the copy commands, so you can run just:

```shell
git -C /opt/Data pull
/opt/copy-files.sh
```

## Control the service
When adding new files that you want to appear on the website you need to restart
the service. This process usually takes a minute or two during which the
website will be down momentarily.

The website runs in a Docker container as a so-called systemd service, so you
can use systemd commands to view the current status or start/stop/restart it:

```shell
systemctl status prayer
systemctl restart prayer  # the command you most likely need
systemctl stop prayer
systemctl start prayer
```

## Observe errors
Only files that are valid TEI are actually added to the website. The ones that
are not will be listed on the [error page](https://bedebog.dk/error/db).

Most of the fixes should be easy enough. When you've fixed one or more issues,
push the necessary changes and redo the deployment flow described above.
