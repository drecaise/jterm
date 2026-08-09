workspace "jterm" "Cross-platform Java Swing terminal emulator: tabs, splittable pane grid, saved SSH sessions, SFTP, tunnels, input broadcast." {

    !identifiers flat
    # No auto-summarised (implied) relationships: each C4 level declares its own
    # jterm/jvm/component -> external edges explicitly. Implied edges duplicated every
    # external relationship in the context view (a summary edge plus a component-derived
    # detail edge), which made the edge labels overlap.
    !impliedRelationships false

    model {
        user = person "User" "Runs local shells and connects to remote hosts over SSH." "Person"

        remoteSshd = softwareSystem "Remote SSH daemon" "sshd on the target host reached via TCP." "External"
        osKeyring = softwareSystem "OS keyring" "secret-tool (Linux), security (macOS) or Windows Credential Store." "External"
        sshAgent = softwareSystem "SSH agent" "Unix-domain socket, OpenSSH named pipe or PuTTY Pageant." "External"
        localShell = softwareSystem "Local shell" "Login shell (/bin/bash, powershell, wsl.exe) spawned via pty4j." "External"
        fileSystem = softwareSystem "Local file system" "OS config dir under ~/.config/jterm etc.; holds sessions.json, credentials.json, icons.json, keymap.json." "External"

        jterm = softwareSystem "jterm" "Java 21 Swing terminal emulator." {

            jvm = container "jterm JVM process" "Java 21 Swing app; single OS process hosting every component." "Java 21 / Swing" "JVM" {

                # ---------- UI layer ----------
                mainWindow          = component "MainWindow"          "Top-level JFrame; installs the global KeyEventDispatcher for keymap shortcuts." "Swing"      "UI"
                windowTopology      = component "WindowTopology"      "Registry of open windows; routes shortcut dispatch to the focused window."      "Swing"      "UI"
                tabPane             = component "TabPane"              "JTabbedPane wrapper; hosts one PaneGrid per tab."                                 "Swing"      "UI"
                paneGrid            = component "PaneGrid"             "Uniform 3x3 R x C grid; weight-sized rows/cols with draggable gutter dividers (WeightedGridLayout)." "Swing" "UI"
                terminalPane        = component "TerminalPane"         "Hosts a JediTermWidget; wires session connector through BroadcastingTtyConnector." "Swing / JediTerm" "UI"
                sessionSidebar      = component "SessionSidebar"       "Tree of folders and SSH sessions; drag source for DnD launches."                  "Swing"      "UI"
                sftpPane            = component "SftpPane"             "Remote file browser backed by MINA SFTP client."                                  "Swing / MINA SFTP" "UI"
                themeManager        = component "ThemeManager"         "FlatLaf light/dark; exposes ThemeColors record consumed by JediTerm."             "FlatLaf"    "UI"
                sessionDropHandler  = component "SessionDropHandler"   "Drop target on each pane; decides split direction via DropRegion."                "Swing DnD"  "UI"

                # ---------- Terminal / session layer ----------
                terminalSession     = component "TerminalSession"      "Interface every pane drives (title, profile, TtyConnector)."                     "Java"       "Terminal"
                localSession        = component "LocalSession"         "pty4j PtyProcess wrapped by PtyTtyConnector."                                     "pty4j"      "Terminal"
                sshSession          = component "SshSession"           "MINA SSHD ChannelShell wrapped by SshTtyConnector; keep-alive and reconnect."     "Apache MINA SSHD" "Terminal"
                sessionFactory      = component "SessionFactory"       "Creates local/WSL sessions synchronously and SSH sessions via SwingWorker."       "Java"       "Terminal"
                connectionService   = component "ConnectionService"    "Orchestrates SSH connect off-EDT; resolves credentials on the EDT first."         "Java"       "Terminal"
                agentSupport        = component "AgentSupport"         "Per-OS ssh-agent endpoint discovery (socket, named pipe, Pageant)."               "Java"       "Terminal"
                jdkAgentProxy       = component "JdkAgentProxy"        "JDK 19+ Unix-domain-socket agent client; reuses MINA's AbstractAgentProxy."       "Java NIO"   "Terminal"
                broadcastConnector  = component "BroadcastingTtyConnector" "Wraps each pane's real connector; fans writes out via BroadcastBus in broadcast mode." "Java" "Terminal"
                knownHostsVerifier  = component "JtermKnownHostsVerifier" "TOFU host-key verifier against ~/.ssh/known_hosts."                             "Java"       "Terminal"
                sshConnect          = component "SshConnect"           "Connect + auth for every hop (shell, SFTP, tunnels); owns the jump-host chain."   "Apache MINA SSHD" "Terminal"
                userInteraction     = component "JtermUserInteraction" "MINA UserInteraction: interactive password / keyboard-interactive fallback after publickey auth fails." "Apache MINA SSHD" "Terminal"

                # ---------- Security layer ----------
                vaultManager        = component "VaultManager"         "Unlocks CredentialVault on the EDT; remembers master password via OS keyring."    "Java"       "Security"
                credentialVault     = component "CredentialVault"      "AES-GCM vault of SSH passwords; PBKDF2-wrapped random vault key."                 "AES-GCM / PBKDF2" "Security"
                credentialResolver  = component "CredentialResolver"   "Resolves SSH and jump-host credentials from vault or prompt."                     "Java"       "Security"
                masterPwKeyring     = component "MasterPasswordKeyring" "Wraps native OS keyring: secret-tool / security / java-keyring JNA."             "Java + native CLI" "Security"

                # ---------- Persistence / cross-cutting ----------
                sessionStore        = component "SessionStore"         "Loads and saves the SshSessionConfig / FolderNode tree from sessions.json."      "Jackson"    "Persistence"
                jsonStore           = component "JsonStore"            "Atomic Jackson-based persistence with corrupt-file preservation."                  "Jackson"    "Persistence"
                appSettings         = component "AppSettings"          "Mutable singleton; persists settings.json (theme, font, TOFU, scrollback, etc.)." "Jackson"    "Persistence"
                keymap              = component "Keymap"                "Action-to-KeyStroke bindings from keymap.json; defaults in TermAction."           "Jackson"    "Persistence"
                iconLibrary         = component "IconLibrary"           "Bundled SVG icons plus user imports persisted to icons.json."                     "Jackson"    "Persistence"
                macroLibrary        = component "MacroLibrary"          "Named keystroke sequences and hotkeys from macros.json."                          "Jackson"    "Persistence"
                highlightLibrary    = component "HighlightLibrary"      "Keyword highlight lists from highlights.json."                                    "Jackson"    "Persistence"

                # ---------- Relationships: UI ----------
                mainWindow -> windowTopology "Registers window and routes shortcuts"
                mainWindow -> tabPane "Hosts tabs"
                mainWindow -> themeManager "Applies LaF and terminal colors"
                mainWindow -> keymap "Reads shortcut bindings"
                tabPane -> paneGrid "One grid per tab"
                paneGrid -> terminalPane "Owns each cell"
                paneGrid -> sessionFactory "Creates sessions on split"
                paneGrid -> broadcastConnector "Wraps every pane's connector"
                terminalPane -> terminalSession "Reads title, profile, TtyConnector"
                terminalPane -> broadcastConnector "Drives JediTermWidget through the wrapper"
                terminalPane -> highlightLibrary "Applies highlight rules to output"
                terminalPane -> themeManager "Reads ThemeColors"
                sessionSidebar -> sessionStore "Reads and edits the session tree"
                sessionSidebar -> iconLibrary "Renders session icons"
                sessionSidebar -> sessionFactory "Launches sessions via DnD"
                sftpPane -> sshSession "Opens SFTP subsystem on an existing connection"
                sessionDropHandler -> paneGrid "Calls split on drop"
                sessionDropHandler -> sessionFactory "Creates the new session"
                sessionSidebar -> sessionDropHandler "Drags a session onto a pane drop target"
                terminalPane -> sessionFactory "Requests a session when a cell is filled"

                # ---------- Relationships: Terminal ----------
                sessionFactory -> localSession "Creates local / WSL sessions"
                sessionFactory -> connectionService "Delegates SSH connect"
                connectionService -> credentialResolver "Resolves passwords on the EDT"
                connectionService -> sshSession "Delivers connected session"
                sshSession -> sshConnect "Authenticates the connection, then opens ChannelShell"
                sftpPane -> sshConnect "Dials a dedicated connection when reconnecting"
                sshConnect -> knownHostsVerifier "Verifies host key on connect"
                sshConnect -> agentSupport "Registers agent identities"
                sshConnect -> userInteraction "Installs the interactive auth fallback"
                userInteraction -> credentialResolver "Prompts for a password when publickey auth is exhausted"
                agentSupport -> jdkAgentProxy "Talks to Unix ssh-agent socket"
                agentSupport -> sshAgent "Named pipe / Pageant on Windows"
                jdkAgentProxy -> sshAgent "Reads identities and signs challenges"
                localSession -> localShell "Spawns child process via pty4j"
                sshSession -> remoteSshd "SSH channel over TCP"
                broadcastConnector -> terminalSession "Reads and writes the underlying connector"
                broadcastConnector -> broadcastConnector "Fans writes to sibling connectors via BroadcastBus"
                connectionService -> terminalPane "Hands the connected session back on the EDT"

                # ---------- Relationships: Security ----------
                credentialResolver -> vaultManager "Requests vault unlock"
                credentialResolver -> credentialVault "Reads and writes saved secrets once unlocked"
                vaultManager -> credentialVault "Decrypts entries after unlock"
                vaultManager -> masterPwKeyring "Loads or stores master password"
                masterPwKeyring -> osKeyring "Reads and writes native OS secret"
                credentialVault -> jsonStore "Persists credentials.json"

                # ---------- Relationships: Persistence ----------
                sessionStore -> jsonStore "Persists sessions.json"
                appSettings -> jsonStore "Persists settings.json"
                keymap -> jsonStore "Persists keymap.json"
                iconLibrary -> jsonStore "Persists icons.json"
                macroLibrary -> jsonStore "Persists macros.json"
                highlightLibrary -> jsonStore "Persists highlights.json"
                jsonStore -> fileSystem "Atomic writes to the OS config dir"
            }
        }

        # ---------- External-system relationships ----------
        user -> jterm "Opens tabs, splits panes, launches sessions"
        jterm -> remoteSshd "SSH shell + SFTP + tunnels"
        jterm -> osKeyring "Stores and retrieves master password"
        jterm -> sshAgent "Public-key auth via agent identities"
        jterm -> localShell "Runs interactive shells locally / in WSL"
        jterm -> fileSystem "Reads and writes JSON config files"

        # ---------- Container-level external relationships (the jterm JVM process) ----------
        # Declared explicitly because implied relationships are disabled; these are what the
        # Containers and Deployment views draw to the OS-native peers.
        jvm -> remoteSshd "SSH channel over TCP"
        jvm -> osKeyring "Reads and writes native OS secret"
        jvm -> sshAgent "Named pipe / Pageant on Windows"
        jvm -> localShell "Spawns child process via pty4j"
        jvm -> fileSystem "Atomic writes to the OS config dir"

        # ---------- Deployment environments ----------
        deploymentEnvironment "Linux" {
            deploymentNode "Linux workstation" "Any glibc distro" {
                deploymentNode "Flatpak sandbox" "org.flatpak.Flatpak" {
                    linuxJvm = containerInstance jvm
                }
                deploymentNode "RPM-installed jterm" "jpackage RPM (Rocky Linux 10)" {
                    linuxRpmJvm = containerInstance jvm
                }
                deploymentNode "secret-tool" "libsecret CLI" {
                    linuxKeyring = softwareSystemInstance osKeyring
                }
                deploymentNode "ssh-agent" "OpenSSH Unix socket" {
                    linuxAgent = softwareSystemInstance sshAgent
                }
            }
        }

        deploymentEnvironment "Windows" {
            deploymentNode "Windows workstation" "Windows 10/11" {
                deploymentNode "MSI-installed jterm" "jpackage MSI" {
                    winJvm = containerInstance jvm
                }
                deploymentNode "Windows Credential Store" "java-keyring JNA backend" {
                    winKeyring = softwareSystemInstance osKeyring
                }
                deploymentNode "OpenSSH agent + Pageant" "Named pipe + Pageant window" {
                    winAgent = softwareSystemInstance sshAgent
                }
            }
        }

        deploymentEnvironment "macOS" {
            deploymentNode "macOS workstation" "macOS 13+" {
                deploymentNode "DMG-installed jterm" "jpackage DMG" {
                    macJvm = containerInstance jvm
                }
                deploymentNode "security CLI" "Keychain CLI" {
                    macKeyring = softwareSystemInstance osKeyring
                }
                deploymentNode "ssh-agent" "launchd-managed Unix socket" {
                    macAgent = softwareSystemInstance sshAgent
                }
            }
        }
    }

    views {

        systemContext jterm "context" "System context for jterm." {
            include *
            autolayout tb
        }

        container jterm "containers" "Runtime containers and their external peers." {
            include *
            autolayout lr
        }

        component jvm "components-ui" "UI-layer components inside the JVM process." {
            include mainWindow windowTopology tabPane paneGrid terminalPane sessionSidebar sftpPane themeManager sessionDropHandler
            include terminalSession broadcastConnector highlightLibrary iconLibrary sessionStore keymap
            autolayout tb
        }

        component jvm "components-terminal" "Terminal / session components inside the JVM process." {
            include terminalPane broadcastConnector
            include terminalSession localSession sshSession sshConnect userInteraction sessionFactory connectionService agentSupport jdkAgentProxy knownHostsVerifier
            include credentialResolver credentialVault
            include localShell remoteSshd sshAgent
            autolayout tb
        }

        component jvm "components-security" "Security components and trust boundaries." {
            include credentialResolver vaultManager credentialVault masterPwKeyring
            include jsonStore appSettings sessionStore
            include osKeyring fileSystem
            autolayout tb
        }

        dynamic jvm "dynamic-ssh-connect" "SSH connect: credential resolution on EDT, connect off-EDT, interactive fallback if key auth fails." {
            terminalPane -> sessionFactory "User drops SSH session on pane"
            sessionFactory -> connectionService "connectSshAsync (EDT)"
            connectionService -> credentialResolver "Resolve saved password on EDT"
            credentialResolver -> vaultManager "Unlock vault"
            vaultManager -> credentialVault "Decrypt saved password"
            connectionService -> sshSession "Open ChannelShell (SwingWorker)"
            sshSession -> sshConnect "Connect and authenticate each hop"
            sshConnect -> knownHostsVerifier "Verify host key"
            sshConnect -> agentSupport "Install ssh-agent identities"
            agentSupport -> jdkAgentProxy "Open Unix socket"
            sshConnect -> userInteraction "publickey exhausted; server offers password"
            userInteraction -> credentialResolver "Prompt for a password (marshalled to the EDT)"
            credentialResolver -> credentialVault "Save it if the user asked to remember"
            connectionService -> terminalPane "Hand connected session back (EDT)"
            autolayout lr
        }

        dynamic jvm "dynamic-broadcast" "Broadcast keystrokes fan out through BroadcastingTtyConnector." {
            terminalPane -> broadcastConnector "User types into focused pane"
            broadcastConnector -> terminalSession "Write to owning connector"
            broadcastConnector -> broadcastConnector "Fan out via BroadcastBus"
            autolayout lr
        }

        dynamic jvm "dynamic-drop-to-split" "Drop-to-split: sidebar drag creates a new pane." {
            sessionSidebar -> sessionDropHandler "Drop SSH session onto pane"
            sessionDropHandler -> paneGrid "split(row|col) based on DropRegion"
            sessionDropHandler -> sessionFactory "Create session async"
            sessionFactory -> connectionService "Connect SSH off-EDT"
            connectionService -> terminalPane "Attach connector to new pane (EDT)"
            autolayout lr
        }

        deployment jterm "Linux" "deployment-linux" "Linux deployment: Flatpak sandbox or Rocky Linux 10 RPM." {
            include *
            autolayout tb
        }

        deployment jterm "Windows" "deployment-windows" "Windows MSI deployment." {
            include *
            autolayout tb
        }

        deployment jterm "macOS" "deployment-macos" "macOS DMG deployment." {
            include *
            autolayout tb
        }

        styles {
            element "Person" {
                background "#3949ab"
                color "#ffffff"
                shape person
            }
            element "External" {
                background "#6c757d"
                color "#ffffff"
            }
            element "JVM" {
                background "#1e88e5"
                color "#ffffff"
            }
            element "UI" {
                background "#42a5f5"
                color "#ffffff"
            }
            element "Terminal" {
                background "#26a69a"
                color "#ffffff"
            }
            element "Security" {
                background "#ef5350"
                color "#ffffff"
            }
            element "Persistence" {
                background "#8d6e63"
                color "#ffffff"
            }
        }

        theme default
    }
}
