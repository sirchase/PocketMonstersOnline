package org.pokenet.server.network;

import java.sql.ResultSet;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.mina.common.IoSession;
import org.pokenet.server.GameServer;
import org.pokenet.server.backend.entity.PlayerChar;
import org.pokenet.server.backend.entity.PokemonBox;
import org.pokenet.server.battle.Pokemon;
import org.pokenet.server.battle.Pokemon.ExpTypes;
import org.pokenet.server.battle.mechanics.PokemonNature;
import org.pokenet.server.battle.mechanics.moves.MoveListEntry;

/**
 * Handles logging players in
 * @author shadowkanji
 *
 */
public class LoginManager implements Runnable {
	private Queue<Object []> m_loginQueue;
	private LogoutManager m_logoutManager;
	private Thread m_thread;
	private boolean m_isRunning;
	private MySqlManager m_database;
	
	/**
	 * Default constructor. Requires a logout manager to be passed in so the server
	 * can check if player's data is not being saved as they are logging in.
	 * @param manager
	 */
	public LoginManager(LogoutManager manager) {
		m_database = new MySqlManager();
		m_logoutManager = manager;
		m_loginQueue = new ConcurrentLinkedQueue<Object []>();
		m_thread = new Thread(this);
	}
	
	/**
	 * Attempts to login a player. Upon success, it sends a packet to the player to inform them they are logged in.
	 * @param session
	 * @param username
	 * @param password
	 */
	private void attemptLogin(IoSession session, String username, String password) {
		PlayerChar p;
		try {
			//First connect to the database
			m_database.connect(GameServer.getDatabaseHost(), GameServer.getDatabaseUsername(), GameServer.getDatabasePassword());
			//Then find the member's information
			ResultSet result = m_database.query("SELECT * FROM pn_members WHERE username='" + username + "'");
			result.first();
			//Check if the password is correct
			if(result.getString("password").compareTo(password) == 0) {
				//Now check if they are logged in anywhere else
				if(result.getString("lastLoginServer").equalsIgnoreCase(GameServer.getServerName())) {
					//They are logged in on this server
				} else if(result.getString("lastLoginServer").equalsIgnoreCase("null")) {
					//They are not logged in elsewhere, set the current login to the current server
					m_database.query("UPDATE pn_members SET lastLoginServer='" + GameServer.getServerName() + "'WHERE username='" + username + "'");
					m_database.query("UPDATE pn_members SET lastLoginIP='" + session.getRemoteAddress() + "' WHERE username='" + username + "'");
					p = getPlayerObject(result);
					p.setSession(session);
					session.setAttribute("player", p);
				} else {
					//They are logged in somewhere else, do not log them in
					return;
				}
			} else {
				//Password is wrong, say so.
				return;
			}
			m_database.close();
		} catch (Exception e) {
			//TODO: Do something if login failed
		}

	}
	
	/**
	 * Places a player in the login queue
	 * @param session
	 * @param username
	 * @param password
	 */
	public void queuePlayer(IoSession session, String username, String password) {
		if(!m_logoutManager.containsPlayer(username))
			m_loginQueue.add(new Object[] {session, username, password});
		else {
			//TODO: Informs the player that they are still being logged out 
		}
	}

	/**
	 * Called by Thread.start()
	 */
	public void run() {
		Object [] o;
		IoSession session;
		String username;
		String password;
		while(m_isRunning) {
			synchronized(m_loginQueue) {
				try {
					if(m_loginQueue.peek() != null) {
						o = m_loginQueue.poll();
						session = (IoSession) o[0];
						username = (String) o[1];
						password = (String) o[2];
						this.attemptLogin(session, username, password);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
				try {
					Thread.sleep(200);
				} catch (Exception e) {}
			}
		}
	}
	
	/**
	 * Starts the login manager
	 */
	public void start() {
		m_isRunning = true;
		m_thread.start();
	}
	
	/**
	 * Stops the login manager
	 */
	public void stop() {
		m_isRunning = false;
	}

	/**
	 * Returns a playerchar object from a resultset of player data
	 * @param data
	 * @return
	 */
	private PlayerChar getPlayerObject(ResultSet result) {
		try {
			PlayerChar p = new PlayerChar();
			Pokemon [] party = new Pokemon[6];
			
			p.setName(result.getString("username"));
			p.setVisible(true);
			//Set co-ordinates
			p.setX(result.getInt("x"));
			p.setY(result.getInt("y"));
			p.setMapX(result.getInt("mapX"));
			p.setMapY(result.getInt("mapY"));
			p.setId(result.getInt("id"));
			//Set money and skills
			p.setSprite(result.getInt("sprite"));
			p.setMoney(result.getInt("money"));
			p.setNpcMultiplier(Double.parseDouble(result.getString("npcMul")));
			p.setHerbalismExp(result.getInt("skHerb"));
			p.setCraftingExp(result.getInt("skCraft"));
			p.setFishingExp(result.getInt("skFish"));
			p.setTrainingExp(result.getInt("skTrain"));
			p.setCoordinatingExp(result.getInt("skCoord"));
			p.setBreedingExp(result.getInt("skBreed"));
			//Retrieve refences to all Pokemon
			int pokesId = result.getInt("pokemons");
			ResultSet pokemons = m_database.query("SELECT * FROM pn_mypokes WHERE id='" + pokesId + "'");
			pokemons.first();
			p.setDatabasePokemon(pokemons);
			//Attach party
			ResultSet partyInfo = m_database.query("SELECT * FROM pn_party WHERE id='" + pokemons.getInt("party") + "'");
			partyInfo.first();
			for(int i = 0; i < 6; i++) {
				party[i] = partyInfo.getInt("pokemon" + i) != -1 ? 
						getPokemonObject(m_database.query("SELECT * FROM pn_pokemon WHERE id='" + partyInfo.getInt("pokemon" + i) + "'"))
						: null;
			}
			p.setParty(party);
			//Attach boxes
			PokemonBox[] boxes = new PokemonBox[9];
			ResultSet boxInfo;
			for(int i = 0; i < 9; i++) {
				/*
				 * -1 is stored in the database if no box exists
				 */
				if(pokemons.getInt("box" + i) != -1) {
					boxInfo = m_database.query("SELECT * FROM pn_box WHERE id='" + pokemons.getInt("box" + i) + "'");
					boxInfo.first();
					for(int j = 0; j < 30; j++) {
						/*
						 * -1 stored in the database if no pokemon exists
						 */
						boxes[i] = new PokemonBox();
						boxes[i].setDatabaseId(pokemons.getInt("box" + i));
						if(boxInfo.getInt("pokemon" + j) != -1) {
							boxes[i].setPokemon(j, getPokemonObject(m_database.query("SELECT * FROM pn_pokemon WHERE id='" + boxInfo.getInt("pokemon" + j) + "'")));
						} else {
							boxes[i].setPokemon(j, null);
						}
					}
				}
			}
			p.setBoxes(boxes);
			return p;
		} catch (Exception e) {
			return null;
		}
	}
	
	/**
	 * Returns a Pokemon object based on a set of data
	 * @param data
	 * @return
	 */
	private Pokemon getPokemonObject(ResultSet data) {
		if(data != null) {
			try {
				data.first();
				/*
				 * First generate the Pokemons moves
				 */
				MoveListEntry [] moves = new MoveListEntry[4];
				moves[0] = (data.getString("move0") != null && !data.getString("move0").equalsIgnoreCase("null") ?
						GameServer.getServiceManager().getDataService().getMovesList().getMove(data.getString("move0")) :
							null);
				moves[1] = (data.getString("move1") != null && !data.getString("move1").equalsIgnoreCase("null") ?
						GameServer.getServiceManager().getDataService().getMovesList().getMove(data.getString("move1")) :
							null);
				moves[2] = (data.getString("move2") != null && !data.getString("move2").equalsIgnoreCase("null") ?
						GameServer.getServiceManager().getDataService().getMovesList().getMove(data.getString("move2")) :
							null);
				moves[3] = (data.getString("move3") != null && !data.getString("move3").equalsIgnoreCase("null") ?
						GameServer.getServiceManager().getDataService().getMovesList().getMove(data.getString("move3")) :
							null);
				/*
				 * Create the new Pokemon
				 */
				Pokemon p = new Pokemon(
						GameServer.getServiceManager().getDataService().getBattleMechanics(),
						GameServer.getServiceManager().getDataService().getSpeciesDatabase().getSpecies(
								GameServer.getServiceManager().getDataService().getSpeciesDatabase().getPokemonByName(data.getString("speciesName")))
						,
						PokemonNature.getNatureByName(data.getString("nature")),
						data.getString("abilityName"),
						data.getString("itemName"),
						data.getInt("gender"),
						data.getInt("level"),
						new int[] { 
							data.getInt("ivHP"),
							data.getInt("ivATK"),
							data.getInt("ivDEF"),
							data.getInt("ivSPD"),
							data.getInt("ivSPATK"),
							data.getInt("ivSPDEF")},
						new int[] { 
							data.getInt("evHP"),
							data.getInt("evATK"),
							data.getInt("evDEF"),
							data.getInt("evSPD"),
							data.getInt("evSPATK"),
							data.getInt("evSPDEF")},
						moves,
						new int[] {
							data.getInt("ppUp0"),
							data.getInt("ppUp1"),
							data.getInt("ppUp2"),
							data.getInt("ppUp3")
						});
				p.reinitialise();
				/*
				 * Set exp, nickname, isShiny and exp gain type
				 */
				p.setBaseExp(data.getInt("baseExp"));
				p.setExp(Double.parseDouble(data.getString("exp")));
				p.setName(data.getString("name"));
				p.setHappiness(data.getInt("happiness"));
				p.setShiny(Boolean.parseBoolean(data.getString("isShiny")));
				p.setExpType(ExpTypes.valueOf(data.getString("expType")));
				p.setOriginalTrainer(data.getString("originalTrainerName"));
				p.setDatabaseID(data.getInt("id"));
				p.setIsFainted(Boolean.parseBoolean(data.getString("isFainted")));
				/*
				 * Sets the stats
				 */
				p.setRawStat(0, data.getInt("hp"));
				p.setRawStat(1, data.getInt("atk"));
				p.setRawStat(2, data.getInt("def"));
				p.setRawStat(3, data.getInt("speed"));
				p.setRawStat(4, data.getInt("spATK"));
				p.setRawStat(5, data.getInt("spDEF"));
				/*
				 * Sets the pp information
				 */
				p.setPp(0, data.getInt("pp0"));
				p.setPp(1, data.getInt("pp1"));
				p.setPp(2, data.getInt("pp2"));
				p.setPp(3, data.getInt("pp3"));
				p.setMaxPP(0, data.getInt("maxpp0"));
				p.setMaxPP(0, data.getInt("maxpp1"));
				p.setMaxPP(0, data.getInt("maxpp2"));
				p.setMaxPP(0, data.getInt("maxpp3"));
				p.setPpUp(0, data.getInt("ppUp0"));
				p.setPpUp(0, data.getInt("ppUp1"));
				p.setPpUp(0, data.getInt("ppUp2"));
				p.setPpUp(0, data.getInt("ppUp3"));
				return p;
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return null;
	}
}
