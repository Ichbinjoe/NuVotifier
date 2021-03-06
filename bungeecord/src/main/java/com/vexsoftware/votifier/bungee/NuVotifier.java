package com.vexsoftware.votifier.bungee;

import com.google.common.collect.ImmutableMap;
import com.vexsoftware.votifier.VoteHandler;
import com.vexsoftware.votifier.VotifierPlugin;
import com.vexsoftware.votifier.bungee.events.VotifierEvent;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.net.VotifierSession;
import com.vexsoftware.votifier.net.protocol.VoteInboundHandler;
import com.vexsoftware.votifier.net.protocol.VotifierGreetingHandler;
import com.vexsoftware.votifier.net.protocol.VotifierProtocolDifferentiator;
import com.vexsoftware.votifier.net.protocol.v1crypto.RSAIO;
import com.vexsoftware.votifier.net.protocol.v1crypto.RSAKeygen;
import com.vexsoftware.votifier.util.KeyCreator;
import com.vexsoftware.votifier.util.TokenUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.Key;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class NuVotifier extends Plugin implements VoteHandler, VotifierPlugin {

    /** The server channel. */
    private Channel serverChannel;

    /** The event group handling the channel. */
    private NioEventLoopGroup serverGroup;

    /** The RSA key pair. */
    private KeyPair keyPair;

    /** Debug mode flag */
    private boolean debug;

    /** Keys used for websites. */
    private Map<String, Key> tokens = new HashMap<>();

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }

        // Handle configuration.
        File config = new File(getDataFolder() + "/config.yml");
        File rsaDirectory = new File(getDataFolder() + "/rsa");
        Configuration configuration;

        if (config.exists()) {
            try {
                configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(config);
            } catch (IOException e) {
                throw new RuntimeException("Unable to load configuration", e);
            }
        } else {
            try {
                // First time run - do some initialization.
                getLogger().info("Configuring Votifier for the first time...");

                // Initialize the configuration file.
                config.createNewFile();

                configuration = new Configuration();
                // With BungeeCord, we can usually assume 0.0.0.0 as the host, but we should have the user check it.
                configuration.set("host", "0.0.0.0");
                configuration.set("port", 8192);
                configuration.set("debug", false);

				/*
				 * Remind hosted server admins to be sure they have the right
				 * port number.
				 */
                getLogger().info("------------------------------------------------------------------------------");
                getLogger().info("Assigning Votifier to listen on port 8192. If you are hosting Craftbukkit on a");
                getLogger().info("shared server please check with your hosting provider to verify that this port");
                getLogger().info("is available for your use. Chances are that your hosting provider will assign");
                getLogger().info("a different port, which you need to specify in config.yml");
                getLogger().info("------------------------------------------------------------------------------");

                String token = TokenUtil.newToken();
                configuration.set("tokens", ImmutableMap.of("default", token));
                getLogger().info("Your default Votifier token is " + token + ".");
                getLogger().info("You will need to provide this token when you submit your server to a voting");
                getLogger().info("list.");
                getLogger().info("------------------------------------------------------------------------------");
                ConfigurationProvider.getProvider(YamlConfiguration.class).save(configuration, config);
            } catch (Exception ex) {
                throw new RuntimeException("Unable to create configuration file", ex);
            }
        }

        /*
		 * Create RSA directory and keys if it does not exist; otherwise, read
		 * keys.
		 */
        try {
            if (!rsaDirectory.exists()) {
                rsaDirectory.mkdir();
                keyPair = RSAKeygen.generate(2048);
                RSAIO.save(rsaDirectory, keyPair);
            } else {
                keyPair = RSAIO.load(rsaDirectory);
            }
        } catch (Exception ex) {
            throw new RuntimeException("Error reading configuration file or RSA tokens", ex);
        }

        // Load Votifier tokens.
        Configuration tokenSection = configuration.getSection("tokens");

        if (configuration.get("tokens") != null) {
            for (String s : tokenSection.getKeys()) {
                tokens.put(s, KeyCreator.createKeyFrom(tokenSection.getString(s)));
                getLogger().info("Loaded token for website: " + s);
            }
        } else {
            String token = TokenUtil.newToken();
            configuration.set("tokens", ImmutableMap.of("default", token));
            tokens.put("default", KeyCreator.createKeyFrom(token));
            try {
                ConfigurationProvider.getProvider(YamlConfiguration.class).save(configuration, config);
            } catch (IOException e) {
                throw new RuntimeException("Error generating Votifier token", e);
            }
            getLogger().info("------------------------------------------------------------------------------");
            getLogger().info("No tokens were found in your configuration, so we've generated one for you.");
            getLogger().info("Your default Votifier token is " + token + ".");
            getLogger().info("You will need to provide this token when you submit your server to a voting");
            getLogger().info("list.");
            getLogger().info("------------------------------------------------------------------------------");
        }

        // Initialize the receiver.
        final String host = configuration.getString("host", "0.0.0.0");
        final int port = configuration.getInt("port", 8192);
        debug = configuration.getBoolean("debug", false);
        if (debug)
            getLogger().info("DEBUG mode enabled!");

        // Must set up server asynchronously due to BungeeCord goofiness.
        getProxy().getScheduler().runAsync(this, new Runnable() {
            @Override
            public void run() {
                serverGroup = new NioEventLoopGroup(1);

                new ServerBootstrap()
                        .channel(NioServerSocketChannel.class)
                        .group(serverGroup)
                        .childHandler(new ChannelInitializer<NioSocketChannel>() {
                            @Override
                            protected void initChannel(NioSocketChannel channel) throws Exception {
                                channel.attr(VotifierSession.KEY).set(new VotifierSession());
                                channel.attr(VotifierPlugin.KEY).set(NuVotifier.this);
                                channel.pipeline().addLast("greetingHandler", new VotifierGreetingHandler());
                                channel.pipeline().addLast("protocolDifferentiator", new VotifierProtocolDifferentiator());
                                channel.pipeline().addLast("voteHandler", new VoteInboundHandler(NuVotifier.this));
                            }
                        })
                        .bind(host, port)
                        .addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future) throws Exception {
                                if (future.isSuccess()) {
                                    serverChannel = future.channel();
                                    getLogger().info("Votifier enabled.");
                                } else {
                                    getLogger().log(Level.SEVERE, "Votifier was not able to bind to " + future.channel().localAddress(), future.cause());
                                }
                            }
                        });
            }
        });
    }

    @Override
    public void onDisable() {
        // Shut down the network handlers.
        if (serverChannel != null)
            serverChannel.close();
        serverGroup.shutdownGracefully();
        getLogger().info("Votifier disabled.");
    }

    @Override
    public void onVoteReceived(final Vote vote, VotifierSession.ProtocolVersion protocolVersion) throws Exception {
        if (debug) {
            if (protocolVersion == VotifierSession.ProtocolVersion.ONE) {
                getLogger().info("Got a protocol v1 vote record -> " + vote);
            } else {
                getLogger().info("Got a protocol v2 vote record -> " + vote);
            }
        }

        getProxy().getScheduler().runAsync(this, new Runnable() {
            @Override
            public void run() {
                getProxy().getPluginManager().callEvent(new VotifierEvent(vote));
            }
        });
    }

    @Override
    public void onError(Channel channel, Throwable throwable) {
        if (debug) {
            getLogger().log(Level.SEVERE, "Unable to process vote from " + channel.remoteAddress(), throwable);
        } else {
            getLogger().log(Level.SEVERE, "Unable to process vote from " + channel.remoteAddress());
        }
    }

    @Override
    public Map<String, Key> getTokens() {
        return tokens;
    }

    @Override
    public KeyPair getProtocolV1Key() {
        return keyPair;
    }

    @Override
    public String getVersion() {
        return getDescription().getVersion();
    }
}
