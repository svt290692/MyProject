/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ru.MainGame.PlayersHanding;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.input.FlyByCamera;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.network.Client;
import com.jme3.network.Message;
import com.jme3.network.MessageListener;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import de.lessvoid.nifty.EndNotify;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import ru.MainGame.CurrentPlayer;
import ru.MainGame.Dice;
import ru.MainGame.Events.StepEvent;
import ru.MainGame.GameState;
import ru.MainGame.GlobalLogConfig;
import ru.MainGame.Gui.MenuState;
import ru.MainGame.HeapState;
import ru.MainGame.Network.FromBothSides.ExtendedSpecificationMessage;
import ru.MainGame.Network.FromServerToPlayers.StartGameMessage;
import ru.MainGame.Network.MessageSpecification;
import ru.MainGame.Network.NumsOfDice;
import ru.MainGame.Network.StatusPlayer;
import static ru.MainGame.Network.StatusPlayer.*;
import static ru.MainGame.PlayersHanding.WordsKeeper.*;
import ru.MainGame.Network.StepToSend;
import ru.MainGame.TableHanding.AnimationEventCounter;
import ru.MainGame.TableHanding.GoatRules;
import ru.MainGame.TableHanding.Rules;
import ru.MainGame.TableHanding.TableState;

/**
 * main state that will process main player input and game events
 * @author svt
 */
public class PlayersState extends AbstractAppState{

    SimpleApplication sApp;

    private Node guiNode;
    private InputManager inputManager;
    private FlyByCamera flyCam;

    private final HeapState heap;
    private final TableState table;
    private final Rules rules;
    private final GameState gameState;
    private MainPlayer mainPlayer;
    
    PickingListener mMouseListener;

    private static final Logger LOG = Logger.getLogger(PlayersState.class.getName());

    private static enum MappingsToInput{
	PICK("Left mouse pick"),
        CLEAR("clear"),
        ESC_MENU("MENU_REQUEST");

	private MappingsToInput(String val) {
	    this.map = val;
	}
	String map;

	@Override
	public String toString() {
	    return map;
	}
    }

    private boolean isNetGameStarted = false;
    private boolean isMainPlayerStepWait = false;
    private boolean isCantStep = false;
    private boolean waitingScore = false;
    private AtomicBoolean markToExit = new AtomicBoolean(false);
    private AtomicBoolean fish = new AtomicBoolean(false);
    
    private static List<PlayersPlaces> busyPlaces = new ArrayList<>();
    private final Queue<Message> queueUnprocessedMessages = new ConcurrentLinkedQueue<>();
    private final List<AbstractPlayer> mAllPlayers = new LinkedList<>();
    private Queue<String> queuePlayersToAllowSteps = null;
    
    OnlineClientHandler mOnlineHandler;

    public PlayersState(HeapState heap, TableState table, Rules rules,GameState gameState) {
	this.heap = heap;
	this.table = table;
	this.rules = rules;
        this.gameState = gameState;
        GlobalLogConfig.initLoggerFromGlobal(LOG);
    }
    
    public static void registerPlace(PlayersPlaces place){
        busyPlaces.add(place);
    }

    public static void unregisterPlace(PlayersPlaces place){
        busyPlaces.remove(place);
    }

    @Override
    public void initialize(AppStateManager stateManager, Application app) {
	this.sApp = (SimpleApplication) app;
	this.guiNode = sApp.getGuiNode();
	this.inputManager = sApp.getInputManager();
	this.inputManager.setCursorVisible(true);
	this.flyCam = sApp.getFlyByCamera();
	this.flyCam.setDragToRotate(true);
        this.mOnlineHandler = new OnlineClientHandler();
        
        try {
            MainPlayerClient client = new MainPlayerClient(heap, rules,
                    PlayersPlaces.MAIN_PLAYER,table.getNode(), sApp,"127.0.0.1","5511",
                    CurrentPlayer.getInstance().getName(),mOnlineHandler);
            this.mainPlayer = client;
            registerPlace(PlayersPlaces.MAIN_PLAYER);
            this.mAllPlayers.add(client);
        } catch (IOException ex) {
            LOG.log(Level.SEVERE, "The Client can't connect to server because network problem. address: <<{0}>> port<<{1}>> ",
                    new Object[]{"127.0.0.1","5511"});
        }

	initInput();
        mainPlayer.getInterface().addPlayerToTopPanel(
                CurrentPlayer.getInstance().getName(), "Not ready",
                CurrentPlayer.getInstance().getIndexOfAvatar(),true);
        mainPlayer.getInterface().setExitListener(new java.awt.event.ActionListener() {

            @Override
            public void actionPerformed(ActionEvent e) {
                
                exitToMenu();
            }
        });

    }
    
    private void initInput(){
        mMouseListener = new PickingListener();
        
	inputManager.addMapping(MappingsToInput.PICK.map,
		new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
        inputManager.addMapping(MappingsToInput.CLEAR.map,
		new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
        inputManager.addMapping(MappingsToInput.ESC_MENU.map,
		new KeyTrigger(KeyInput.KEY_ESCAPE));

        

        
	inputManager.addListener(mMouseListener,
                MappingsToInput.PICK.map,
                MappingsToInput.CLEAR.map);
        inputManager.addListener( new ActionListener() {

            @Override
            public void onAction(String name, boolean isPressed, float tpf) {
                if(isPressed){
                    mainPlayer.getInterface().makePopupMenu();
                }
            }
        },MappingsToInput.ESC_MENU.map);
        
    }
    
    @Override
    public void update(float tpf) {
        
        if(markToExit.get() == true){
            exitToMenu();
        }
        
	if(mainPlayer.isCursorDiceExists() == true){
	    mainPlayer.setCursorDicePos(
		    inputManager.getCursorPosition());
	}
        if( true == isMainPlayerStepWait){
            if( false == rules.isGameStarted()){
                nullingCanStepData();
                mainPlayer.backlightProcess(true);
            }
            else{
                mainPlayer.backlightProcess(false);
            }
        }

        if(!queueUnprocessedMessages.isEmpty())
            mOnlineHandler.resiveNewMessagesAppProcess();
        
        for(AbstractPlayer player : mAllPlayers){
            player.UpdateFromApplication();
        }
        
        if(isCantStep == true && waitingScore == false){
            Spatial dice = mainPlayer.TakeFromHeapRandom();
            
//            if(null != dice){
//                System.err.println(">>>>DEBUG! HEAP GIVE ME: ==" + dice.getControl(Dice.class));
//            }
            
            if(dice == null){
                mOnlineHandler.sendCheck();
                mainPlayer.getInterface().makePopupText("You Check");
                isCantStep = false;
            }else if(rules.TryMakeTips(dice) == true){
                
                NumsOfDice diceToSend = new NumsOfDice(
                            dice.getControl(Dice.class).getLeftNum(),
                            dice.getControl(Dice.class).getRightNum());
                dice.setUserData(USER_DATA_DICE_CAN_STEP.map, true);
                rules.removeTips();
                isCantStep = false;
                mainPlayer.getInterface().makePopupText("Found!");
                mOnlineHandler.sendMyNewDiceFromHeap(diceToSend);
                
            }else{
                dice.setUserData(USER_DATA_DICE_CAN_STEP.map, false);
                NumsOfDice diceToSend = new NumsOfDice(
                            dice.getControl(Dice.class).getLeftNum(),
                            dice.getControl(Dice.class).getRightNum());
                mOnlineHandler.sendMyNewDiceFromHeap(diceToSend);
            }
        }
        
        if(isNetGameStarted == false){
            if( ! mainPlayer.getHand().isEmpty()){
                mainPlayer.clearGui();
            }
        }
        
    }
    
    @Override
    public void cleanup() {
        if(mainPlayer != null){
            mainPlayer.killPlayer();
        }
        clearInput();
        busyPlaces.clear();
    }
    
    private void clearInput(){
        inputManager.deleteMapping(MappingsToInput.PICK.map);
        inputManager.deleteMapping(MappingsToInput.CLEAR.map);
        inputManager.deleteMapping(MappingsToInput.ESC_MENU.map);
        inputManager.removeListener(mMouseListener);
    }
    
    public void exitToMenu(){
        mainPlayer.clearGui();
        
        sApp.getStateManager().detach(gameState);
        sApp.getStateManager().attach(new MenuState());
    }
    
    private void turnNextPlayerStep(){
        if(queuePlayersToAllowSteps.element().equals(CurrentPlayer.getInstance().getName())){
            denieMainStep();
        }
        
        mainPlayer.getInterface().changeStatus(queuePlayersToAllowSteps.element(),
                    "");
        
        
        queuePlayersToAllowSteps.add(queuePlayersToAllowSteps.remove());
        
        while(getPlayer(queuePlayersToAllowSteps.element()).getHand().isEmpty()){
            queuePlayersToAllowSteps.remove();
            if(queuePlayersToAllowSteps.isEmpty())
                return;
        }
        
        if(queuePlayersToAllowSteps.size() == 1 && 
                queuePlayersToAllowSteps.element().equals(
                CurrentPlayer.getInstance().getName())){
            mOnlineHandler.sendMyScoreToServer();
            waitingScore = true;
            return;
        }
        
        mainPlayer.getInterface().changeStatus(queuePlayersToAllowSteps.element(),
                    "Makes\n turn");
        if(queuePlayersToAllowSteps.element().equals(CurrentPlayer.getInstance().getName())){
            allowMainStep();

            
            if(isStepExists() == true){
                mainPlayer.getInterface().makePopupText("Your turn");
                LOG.log(Level.FINE, "Step exists, main player can do step");
            }
            else{
                if(fish.get() == true){
                    waitingScore = true;
                }
                else if(mainPlayer.getHand().size() > 0 && heap.getNode().getChildren().isEmpty() == false){
//                    System.err.println(">>>>DEBUG! ORDER TO HEAP: heap size ==" + heap.getNode().getChildren().size());
                    mainPlayer.getInterface().makePopupText("You must go to heap...");
                    isCantStep = true;
                }
                else if(heap.getNode().getChildren().isEmpty() == true){
                    mOnlineHandler.sendCheck();
                }
            }

            boolean isAllExceptMeEmpty = true;

            for(AbstractPlayer p : mAllPlayers){
                if(!(p instanceof MainPlayer))
                {
                    if(!p.getHand().isEmpty()){
                        isAllExceptMeEmpty = false;
                        break;
                    }
                }
            }
            if(isAllExceptMeEmpty == true){
                mOnlineHandler.sendMyScoreToServer();
            }
        }
    }
    
    private boolean isStepExists(){
        nullingCanStepData();
        boolean haveStep = false;
        for(Spatial s : mainPlayer.getHand()){
            if(rules.TryMakeTips(s) == true){
                haveStep = true;
                s.setUserData(USER_DATA_DICE_CAN_STEP.map, true);
                //DEBUG
//                System.err.println(">>>>DEBUG! STEP EXISTS: " + s.getControl(Dice.class));
            }
            else{
                s.setUserData(USER_DATA_DICE_CAN_STEP.map, false);
//                System.err.println(">>>>DEBUG! STEP NOT EXISTS: with dice : " + s.getControl(Dice.class));
            }
        }
        rules.removeTips();
        return haveStep;
    } 
    
    private void nullingCanStepData(){
        for(Spatial s : mainPlayer.getHand()){
            s.setUserData(USER_DATA_DICE_CAN_STEP.map, false);
        }
    }
    
    private AbstractPlayer getPlayer(String name){
        for(AbstractPlayer p : mAllPlayers)
            if(p.getName().equals(name))
                return p;
        return null;
    }
    
    private void allowMainStep(){
//        sApp.getInputManager().setCursorVisible(true);
        isMainPlayerStepWait = true;
    }
    
    private void denieMainStep(){
//        sApp.getInputManager().setCursorVisible(false);
        isMainPlayerStepWait = false;
    }

    private class PickingListener implements ActionListener{

        @Override
	public void onAction(String name, boolean isPressed, float tpf) {
	    if(name.equals(MappingsToInput.PICK.map) 
                    && isMainPlayerStepWait == true 
                    && AnimationEventCounter.getInstance().isAnimationInTableExists() == false){
		mainPlayer.mouseClick(isPressed);
	    }
            else if(name.equals(MappingsToInput.CLEAR.map)){
                if(true == isPressed){
                    mainPlayer.clearCursor();
                    rules.removeTips();
                }
            }
            
	}
    }
    
    private PlayersPlaces findCorrectPlaceForDistancePlayer(){
            for(PlayersPlaces p : PlayersPlaces.values()){
                if(busyPlaces.contains(p)) continue;
                else return p;
            }
            
            return null;
    }
    
    private void endGame(){
        heap.returnAllDicesToHeap(true);
        isNetGameStarted = false;
        isCantStep = false;
        isMainPlayerStepWait = false;
        rules.endGame();
        rules.removeTips();
        mainPlayer.clearGui();
    }
    
    private class OnlineClientHandler implements MessageListener<Client>{

        @Override
        public void messageReceived(Client source, Message m) {
           LOG.log(Level.INFO, "I am resive message in Players state : {0}", m);
            if(m instanceof ExtendedSpecificationMessage){
                
                ExtendedSpecificationMessage message = (ExtendedSpecificationMessage)m;
                if(message.getSpecification().equals(MessageSpecification.INITIALIZATION)||
                        message.getSpecification().equals(MessageSpecification.NEW_STATUS)||
                        message.getSpecification().equals(MessageSpecification.DISCONNECT)){
                    
                    if(!(message.getWhoSend().equals(CurrentPlayer.getInstance().getName()))){
                        queueUnprocessedMessages.add(message);
                    }
                }
                else if(message.getSpecification().equals(MessageSpecification.STEP)){
                    queueUnprocessedMessages.add(message);
                }else if(message.getSpecification().equals(MessageSpecification.KICK)){
                    JOptionPane.showMessageDialog(null, message.getMessage());
//                    sApp.stop();
//                    exitToMenu();
                    markToExit.set(true);
                }
                else if(message.getSpecification().equals(MessageSpecification.GET_DICE_FROM_HEAP)){
                    queueUnprocessedMessages.add(message);
                }
//                else if(message.getSpecification().equals(MessageSpecification.EMPTY_HAND)){
//                    for(AbstractPlayer p : mAllPlayers){
//                        if(p.getName().equals(message.getWhoSend())){
//                            
//                        }
//                    }
//                }
                else if(message.getSpecification().equals(MessageSpecification.FISH)){
                    queueUnprocessedMessages.add(message);                    
                }
                else if(message.getSpecification().equals(MessageSpecification.SCORE)){
                    queueUnprocessedMessages.add(message);                    
                }
            }
            else if(m instanceof StartGameMessage){
                
                synchronized(this){
                    StartGameMessage message = ((StartGameMessage)m);
                        queueUnprocessedMessages.add(message);
                    while(!queueUnprocessedMessages.isEmpty()){
                        try {
                            this.wait(10);
                        } catch (InterruptedException ex) {
                            Logger.getLogger(PlayersState.class.getName()).log(Level.SEVERE, "interrupt when wait", ex);
                        }
                    }
                    LOG.log(Level.FINE, "Start game part ::: {0}", message.getStartGamePart().toString());
                    for(AbstractPlayer p : mAllPlayers){
                        List<NumsOfDice> mPart = message.getPartOf(p.getName());

                        for(NumsOfDice n : mPart){
                         p.TakeFromHeap(n.getLeft(), n.getRight());
                        }
                    }
                    LOG.log(Level.INFO,"LETS START!!!!");
                    
                }
            }
        }
        
        void makeResivedStepFromDistancePlayer(ExtendedSpecificationMessage message){
        String name = message.getWhoSend();
        StepToSend step = (StepToSend)message.getRestrictedObject();
        if(step == null){
            turnNextPlayerStep();
            if(!name.equals(CurrentPlayer.getInstance().getName()))
                mainPlayer.getInterface().makePopupText("Player : " + name +" Check!");
        }else{
            for(AbstractPlayer p :mAllPlayers ){
                
                if(p.getName().equals(name)){
                    if(message.getMessage() != null && message.getMessage().split(" ")[0].equals("start")){
                        rules.startGame(HeapState.findDiceIn(p.getNode().getChildren(),
                                step.getInHand().getLeft(),step.getInHand().getRight()));
    
                    }
                    else{
                        Spatial onHand = HeapState.findDiceIn(p.getNode().getChildren(),
                                step.getInHand().getLeft(), step.getInHand().getRight());

                        Spatial onTable = HeapState.findDiceIn(table.getNode().getChildren(),
                                step.getInTable().getLeft(), step.getInTable().getRight());

                        if(message.getMessage() != null){ 
                            String m = message.getMessage().split(" ")[0];
//                            try{
                            switch (m) {
                                case "left":
                                    onHand.setUserData(GoatRules.MAPPING_PREF_TO_LEFT, true);
                                    break;
                                case "right":
                                    onHand.setUserData(GoatRules.MAPPING_PREF_TO_LEFT, false);
                                    break;
                            }
//                            }catch(NullPointerException ex){
//                                System.err.println("pipec");
//                            }
                        }
                        
                        StepEvent event = new StepEvent(onTable, onHand, step.getInTableNum(),step.getInHandNum());
                        
                        rules.doStep(event);
                    }
                    p.sortDices();
                    
                    turnNextPlayerStep();
                    
                    if(name.equals(CurrentPlayer.getInstance().getName())){
                        
                        LOG.log(Level.FINEST, "MyHand size =={0}it is == {1}", new Object[]{mainPlayer.getHand().size(), mainPlayer.getHand()});

                        if(mainPlayer.getHand().isEmpty()){
                            mainPlayer.getInterface().makePopupText("You're out!!!");
                            ExtendedSpecificationMessage msg = new ExtendedSpecificationMessage();
                            msg.setWhoSend(CurrentPlayer.getInstance().getName());
                            msg.setSpecification(MessageSpecification.EMPTY_HAND);
                            msg.setStatusPlayer(StatusPlayer.WATCHER);
                            CurrentPlayer.getInstance().getClientOfCurSession().send(msg);
                        }
                    }
                    break;
                }
            }
        }
    }
        
        private void sendMyScoreToServer(){
        int score = 0;
        for(Spatial s : mainPlayer.getHand()){
            Dice d = s.getControl(Dice.class);
            
            if(d.getBothNum() == 0) 
                score += 25;
            else
                score += d.getLeftNum() +d.getRightNum();
        }
        
        ExtendedSpecificationMessage msg = new ExtendedSpecificationMessage(
                MessageSpecification.SCORE, CurrentPlayer.getInstance().getName(),
                IN_GAME, new Integer(score));
        CurrentPlayer.getInstance().getClientOfCurSession().send(msg);
    }
        
        private void resiveNewMessagesAppProcess(){
        Message message = queueUnprocessedMessages.remove();
        if(message instanceof ExtendedSpecificationMessage){
            ExtendedSpecificationMessage extendedMessage = (ExtendedSpecificationMessage)message;
            if(extendedMessage.getSpecification().equals(MessageSpecification.INITIALIZATION)){
                synchronized(this){
                    initializationMessageProcess(extendedMessage);
                    notifyAll();
                }
            }
            else if(extendedMessage.getSpecification().equals(MessageSpecification.NEW_STATUS)){
                mainPlayer.getInterface().changeStatus(extendedMessage.getWhoSend(),extendedMessage.getStatusPlayer().toString());
            }
            
            else if(extendedMessage.getSpecification().equals(MessageSpecification.DISCONNECT)){
                disconnectMessageProcess(extendedMessage);
            }
            else if(extendedMessage.getSpecification().equals(MessageSpecification.STEP)){
                makeResivedStepFromDistancePlayer(extendedMessage);
            }
            else if(extendedMessage.getSpecification().equals(MessageSpecification.GET_DICE_FROM_HEAP)){
                getDiceFromHeapMessageProcess(extendedMessage);
            }
            else if(extendedMessage.getSpecification().equals(MessageSpecification.FISH)){
                sendMyScoreToServer();
                fish.set(true);
//                mainPlayer.getInterface().makeFish(null);
            }
            else if(extendedMessage.getSpecification().equals(MessageSpecification.SCORE)){
                scoreMessageProcess(extendedMessage);
            }
        }
        else{
            startGameMessageProcess((StartGameMessage)message);
        }
    }
        
        private void initializationMessageProcess(ExtendedSpecificationMessage extendedMessage){
            for(AbstractPlayer p : mAllPlayers){
                        if(p.getName().equals(extendedMessage.getWhoSend()))
                            return;
                    }
                    PlayersPlaces place = findCorrectPlaceForDistancePlayer();
                    registerPlace(place);
                    mAllPlayers.add(new DistancePlayer(place,
                            sApp.getRootNode(), heap, extendedMessage.getWhoSend()));
                    
                    int index = (Integer)extendedMessage.getRestrictedObject();
                    mainPlayer.getInterface().addPlayerToTopPanel(
                            extendedMessage.getWhoSend(),extendedMessage.getStatusPlayer().toString(),index,false);
        }
        
        private void disconnectMessageProcess(ExtendedSpecificationMessage extendedMessage){
            mainPlayer.getInterface().removePlayer(extendedMessage.getWhoSend());
                mainPlayer.getInterface().makePopupText("Player : " + extendedMessage.getWhoSend() + " Disconnect");
                
                if(isNetGameStarted){
                    JOptionPane.showMessageDialog(null, "Connection with one of players is refused when gae is running");
                    LOG.log(Level.SEVERE, "Connection with  {0} has been refused, the game back to menu", extendedMessage.getWhoSend());
                    markToExit.set(true);
                    return;
                }
                for(AbstractPlayer p : mAllPlayers){
                    if(p.getName().equals(extendedMessage.getWhoSend())){
                        mAllPlayers.remove(p);
                        unregisterPlace(p.getPlace());
                        break;
                    }
                }
        }
        
        private void getDiceFromHeapMessageProcess(ExtendedSpecificationMessage extendedMessage){
            for(AbstractPlayer p : mAllPlayers){
                    if(p.getName().equals(extendedMessage.getWhoSend())){
                        NumsOfDice dice = (NumsOfDice)extendedMessage.getRestrictedObject();
                        p.TakeFromHeap(dice.getLeft(),dice.getRight());
//                            mainPlayer.getInterface().makePopupText(
//                                    message.getWhoSend() + "take dice from heap");
                    }
                }
        }
        
        private void scoreMessageProcess(ExtendedSpecificationMessage extendedMessage){
            Map<String,Integer> map = (Map<String,Integer>)(extendedMessage.getRestrictedObject());
                List<String> strings = new ArrayList<>();
                
                for(String name : map.keySet()){
                    strings.add("PLAYER : " + name + "____" + map.get(name));
                }
                
                mainPlayer.getInterface().makeScoreDeck(
                        strings,"fish".equals(extendedMessage.getMessage()),new EndNotify() {

                    @Override
                    public void perform() {
                        mainPlayer.getInterface().makeButtonInButtonLayer("Ready");
                    }
                });
                endGame();
                sendExtendedMessage(MessageSpecification.NEW_STATUS,
                        null, null,NOT_READY);
        }
        
        private void startGameMessageProcess(StartGameMessage startMessage){
            
            for(String name :startMessage.getStartGamePart().keySet()){
                mainPlayer.getInterface().changeStatus(name, " ");
            }
            
            queuePlayersToAllowSteps = new ConcurrentLinkedQueue<>(startMessage.getQueueToSteps());
            if(queuePlayersToAllowSteps.element().equals(CurrentPlayer.getInstance().getName())){
                allowMainStep();
                mainPlayer.getInterface().makePopupText("Your first");
            }
            
                mainPlayer.getInterface().changeStatus(queuePlayersToAllowSteps.element(),
                    "Makes\n turn");
                
            mainPlayer.getInterface().removeCurButtonInMenu(null);
            isNetGameStarted = true;
            fish.set(false);
            waitingScore = false;
            
            mainPlayer.getInterface().makePopupText("Lets start!!!");
            
        }
        
        private void sendExtendedMessage(MessageSpecification specific,Object restrictedObject,String message,StatusPlayer status){
        ExtendedSpecificationMessage msg = new ExtendedSpecificationMessage();
        msg.setStatusPlayer(status);
        msg.setSpecification(specific);
        msg.setWhoSend(CurrentPlayer.getInstance().getName());
        msg.setRestrictedObject(restrictedObject);
        msg.setMessage(message);
        CurrentPlayer.getInstance().getClientOfCurSession().send(msg);
    }
        
        private void sendMyNewDiceFromHeap(NumsOfDice dice){
        ExtendedSpecificationMessage msg = new ExtendedSpecificationMessage(
                        MessageSpecification.GET_DICE_FROM_HEAP,
                        CurrentPlayer.getInstance().getName(),
                        IN_GAME,dice);
                CurrentPlayer.getInstance().getClientOfCurSession().send(msg);
    }
        
        private void sendCheck(){
            ExtendedSpecificationMessage msg = new ExtendedSpecificationMessage(
                        MessageSpecification.STEP, CurrentPlayer.getInstance().getName(),
                        StatusPlayer.IN_GAME, null);
                CurrentPlayer.getInstance().getClientOfCurSession().send(msg);
        }
    }
    
    public class DistancePlayer extends AbstractPlayer{

        public DistancePlayer(PlayersPlaces Place, Node rootNode, HeapState heap,String name) {
            super(Place, rootNode, heap,name);
            this.name = name;
        }

        @Override
        public void UpdateFromApplication() {
            if(!queueAddToScreenDices.isEmpty()){
                Spatial s = queueAddToScreenDices.remove();
                s.setLocalRotation(new Quaternion().fromAngles(90 * FastMath.DEG_TO_RAD, 0, 0));
                getNode().attachChild(s);
                sortNodeDices(getNode(), HeapState.getDicesWidth());
            }
        }

        @Override
        public void sortDices() {
            sortNodeDices(myNode, HeapState.getDicesWidth());
        }
    }
}