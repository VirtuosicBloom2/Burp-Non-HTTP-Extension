package josh.nonHttp;
//

import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;


import javax.net.ssl.SSLHandshakeException;


import java.net.InetAddress;

import burp.IBurpExtenderCallbacks;
import josh.nonHttp.events.ProxyEvent;
import josh.nonHttp.events.ProxyEventListener;
import josh.ui.utils.InterceptData;
import josh.utils.events.PythonOutputEvent;
import josh.utils.events.PythonOutputEventListener;
import josh.utils.events.SendClosedEvent;
import josh.utils.events.SendClosedEventListener;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenericMiTMServer
		implements Runnable, ProxyEventListener, PythonOutputEventListener, SendClosedEventListener {

	public int ListenPort;
	public int ServerPort;
	public String ServerAddress;
	public String ServerHostandIP;
	public String CertHostName;
	private boolean killme = false;
	protected boolean isInterceptOn = false;
	private int interceptType = 0; // 0=both, 1=c2s, 2=s2c
	public InterceptData interceptc2s;
	public InterceptData intercepts2c;
	Object svrSock;
	// SSLServerSocket sslSvrSock;
	Socket connectionSocket;
	Object cltSock;
	Vector<Thread> threads = new Vector<Thread>();
	Vector<SendData> sends = new Vector<SendData>();
	HashMap<SendData, SendData> pairs = new HashMap<SendData, SendData>();
	HashMap<Integer, Thread> treads2 = new HashMap<Integer, Thread>();
	boolean isSSL = false;
	boolean isRunning = false;
	public final int INTERCEPT_C2S = 1;
	public final int INTERCEPT_S2C = 2;
	public final int INTERCEPT_BOTH = 0;
	private int IntercetpDirection = 0;
	private IBurpExtenderCallbacks Callbacks;
	private boolean MangleWithPython = false;
	// SendData send;
	// SendData getD;

	public GenericMiTMServer(boolean isSSL, IBurpExtenderCallbacks Callbacks) {
		this.interceptc2s = new InterceptData(null);
		this.intercepts2c = new InterceptData(null);
		this.isSSL = isSSL;
		this.Callbacks = Callbacks;
	}

	public static boolean available(int port) {
		if (port < 1 || port > 65535) {
			return false;
		}

		ServerSocket ss = null;
		DatagramSocket ds = null;
		try {
			ss = new ServerSocket(port);
			ss.setReuseAddress(true);
			ds = new DatagramSocket(port);
			ds.setReuseAddress(true);
			return true;
		} catch (IOException e) {
		} finally {
			if (ds != null) {
				ds.close();
			}

			if (ss != null) {
				try {
					ss.close();
				} catch (IOException e) {

				}
			}
		}

		return false;
	}

	private List _listeners = new ArrayList();
	private List _pylisteners = new ArrayList();

	public synchronized void addEventListener(ProxyEventListener listener) {
		_listeners.add(listener);
	}

	public synchronized void removeEventListener(ProxyEventListener listener) {
		_listeners.remove(listener);
	}

	public synchronized void addPyEventListener(PythonOutputEventListener listener) {
		_pylisteners.add(listener);
	}

	public synchronized void removePyEventListener(PythonOutputEventListener listener) {
		_pylisteners.remove(listener);
	}

	private synchronized void NewDataEvent(ProxyEvent e) {
		ProxyEvent event = e;
		Iterator i = _listeners.iterator();
		while (i.hasNext()) {
			((ProxyEventListener) i.next()).DataReceived(event);
		}
	}

	public synchronized void SendPyOutput(PythonOutputEvent event) {
		Iterator i = _pylisteners.iterator();
		while (i.hasNext()) {
			((PythonOutputEventListener) i.next()).PythonMessages(event);
		}
	}

	private synchronized void InterceptedEvent(ProxyEvent e, boolean isC2S) {
		ProxyEvent event = e;
		event.setMtm(this);
		Iterator i = _listeners.iterator();
		while (i.hasNext()) {
			((ProxyEventListener) i.next()).Intercepted(event, isC2S);
		}

	}

	public boolean isPythonOn() {
		return this.MangleWithPython;
	}

	public void setPythonMange(boolean mangle) {
		this.MangleWithPython = mangle;
	}

	public void KillThreads() {

		// System.out.println("Number of Data buffer threads is: " + threads.size());
		for (int i = 0; i < threads.size(); i++) {
			// System.out.println("Interrrpting Thread");
			try {
				if (sends.get(i).isSSL()) {
					// ((SSLSocket)sends.get(i).sock).shutdownInput();
					// ((SSLSocket)sends.get(i).sock).shutdownOutput();
					((SSLSocket) sends.get(i).sock).close();
				} else {
					// ((Socket)sends.get(i).sock).shutdownInput();
					// ((Socket)sends.get(i).sock).shutdownOutput();
					((Socket) sends.get(i).sock).close();
				}
			} catch (SocketException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				// e.printStackTrace();
			}
			sends.get(i).killme = true;
			threads.get(i).interrupt();

		}

		try {
			if (connectionSocket != null)
				connectionSocket.close();
			if (isSSL)
				((SSLServerSocket) svrSock).close();
			else
				((ServerSocket) svrSock).close();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	@Override
	public void run() {
		Callbacks.printOutput("Starting New Server.");
		this.isRunning = true;
		if (this.ServerAddress == null || this.ServerPort == 0 | this.ListenPort == 0) {
			Callbacks.printOutput("Ports and or Addresses are blank");
			this.isRunning = false;
			return;
		}
		try {
			if (isSSL) {

				DynamicKeyStore test = new DynamicKeyStore();

				String ksPath = test.generateKeyStore("changeit", this.CertHostName);

				KeyStore ks = KeyStore.getInstance("PKCS12");
				ks.load(new FileInputStream(ksPath), "changeit".toCharArray());

				KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

				kmf.init(ks, "changeit".toCharArray());

				X509Certificate[] result = new X509Certificate[ks
						.getCertificateChain(ks.aliases().nextElement()).length];
				// System.out.println(result.length);

				SSLContext serverSSLContext = SSLContext.getInstance("TLSv1.2");
				serverSSLContext.init(kmf.getKeyManagers(), null, null);
				SSLServerSocketFactory serverSSF = serverSSLContext.getServerSocketFactory();
				svrSock = (SSLServerSocket) serverSSF.createServerSocket(this.ListenPort);
				//((SSLServerSocket)svrSock).setEnabledProtocols(new String[] { "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3" });

			} else {
				svrSock = new ServerSocket(this.ListenPort);
			}


			while (true && !killme) {
				try {
					Callbacks.printOutput("New MiTM Instance Created");
					// System.out.println("Number of Threads is: " + threads.size());
					System.out.println("Waiting for connection");
					if (isSSL){
						for(String s : ((SSLServerSocket) svrSock).getEnabledCipherSuites()){
							System.out.println(s);
						}
						connectionSocket = ((SSLServerSocket) svrSock).accept();
					}else
						connectionSocket = ((ServerSocket) svrSock).accept();


					System.out.println("Accepted connection");
					//connectionSocket.setSoTimeout(2000);
					connectionSocket.setReceiveBufferSize(2056);
					connectionSocket.setSendBufferSize(2056);
					connectionSocket.setKeepAlive(true);

					InputStream inFromClient = connectionSocket.getInputStream();
					DataOutputStream outToClient = new DataOutputStream(connectionSocket.getOutputStream());

					// Object cltSock;
					if (isSSL) {
						System.out.println("using ssl");
						TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
							public java.security.cert.X509Certificate[] getAcceptedIssuers() {
								return null;
							}
							public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
							}
							public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
							}
						}};
						SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            			sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

						SSLSocketFactory ssf = sslContext.getSocketFactory();
						//cltSock = (SSLSocket) ssf.createSocket("www.otto-js.com", this.ServerPort);
						String IPV4_PATTERN = "^(([0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])(\\.(?!$)|$)){4}$";
						Pattern pattern = Pattern.compile(IPV4_PATTERN);
						Matcher matcher = pattern.matcher(this.ServerAddress);
						if(matcher.matches()){

							System.out.println("address:" + this.ServerAddress);
							String [] stringOctets = this.ServerAddress.split("\\.");
							System.out.println(stringOctets);
							System.out.println(stringOctets.length);
							byte [] byteOctets = new byte[4];
							byteOctets[0] = (byte) (Integer.parseInt(stringOctets[0]) & 0xFF);
							byteOctets[1] = (byte) (Integer.parseInt(stringOctets[1]) & 0xFF);
							byteOctets[2] = (byte) (Integer.parseInt(stringOctets[2]) & 0xFF);
							byteOctets[3] = (byte) (Integer.parseInt(stringOctets[3]) & 0xFF);
							InetAddress inetAddress = InetAddress.getByAddress(this.CertHostName, byteOctets);
							System.out.println("my address: ");
							System.out.println(inetAddress);
							cltSock = (SSLSocket) ssf.createSocket(inetAddress, this.ServerPort);
						}else{
							cltSock = (SSLSocket) ssf.createSocket(this.ServerAddress, this.ServerPort);	
						}
						//((SSLSocket) cltSock).setSoTimeout(2000);
						((SSLSocket) cltSock).setReceiveBufferSize(2056);
						((SSLSocket) cltSock).setSendBufferSize(2056);
						((SSLSocket) cltSock).setKeepAlive(true);
						//((SSLSocket) cltSock).startHandshake();
					} else {
						cltSock = new Socket(this.ServerAddress, this.ServerPort);
						//((Socket) cltSock).setSoTimeout(2000);
						((Socket) cltSock).setReceiveBufferSize(2056);
						((Socket) cltSock).setSendBufferSize(2056);
						((Socket) cltSock).setKeepAlive(true);
						ServerHostandIP = ((Socket) cltSock).getRemoteSocketAddress().toString();
						if (ServerHostandIP != null && ServerHostandIP.indexOf(":") != -1) {
							ServerHostandIP = ServerHostandIP.split(":")[0];
						}

						if (ServerHostandIP.indexOf('/') == 0) {
							ServerHostandIP = ServerHostandIP.split("/")[1];
						}
					}

					DataOutputStream outToServer;

					InputStream inFromServer;
					if (isSSL) {
						outToServer = new DataOutputStream(((SSLSocket) cltSock).getOutputStream());
						inFromServer = ((SSLSocket) cltSock).getInputStream();

					} else {
						outToServer = new DataOutputStream(((Socket) cltSock).getOutputStream());
						inFromServer = ((Socket) cltSock).getInputStream();
					}

					// Send data from client to server
					System.out.println("Send Data: " + connectionSocket.getPort() + " :: "
							+ connectionSocket.getLocalPort() + " :: " + pairs.size());
					SendData client2ServerSD = new SendData(this, true, isSSL);
					/*
					 * if(pairs.size() != 0){
					 * send = (SendData)pairs.keySet().toArray()[0];
					 * }else{
					 */

					client2ServerSD.addEventListener(GenericMiTMServer.this);
					client2ServerSD.addPyEventListener(this);
					client2ServerSD.addSendClosedEventListener(this);
					client2ServerSD.Name = "c2s";
					// }
					client2ServerSD.sock = connectionSocket;
					client2ServerSD.in = inFromClient;
					client2ServerSD.out = outToServer;

					// Send data from server to Client
					SendData server2ClientSD = new SendData(this, false, isSSL);
					/*
					 * if(pairs.size() != 0){
					 * getD = ((SendData)pairs.keySet().toArray()[0]).doppel;
					 * }else{
					 */
					server2ClientSD.addEventListener(GenericMiTMServer.this);
					server2ClientSD.addPyEventListener(this);
					server2ClientSD.addSendClosedEventListener(this);
					server2ClientSD.Name = "s2c";
					// }
					server2ClientSD.sock = cltSock;
					server2ClientSD.in = inFromServer;
					server2ClientSD.out = outToClient;

					// if(pairs.size() == 0){
					client2ServerSD.doppel = server2ClientSD;
					server2ClientSD.doppel = client2ServerSD;
					sends.add(client2ServerSD);
					sends.add(server2ClientSD);
					synchronized (this) {
						System.out.println("Creating pairs");
						pairs.put(client2ServerSD, server2ClientSD);
					}
					Thread c2s = new Thread(client2ServerSD);
					Thread s2c = new Thread(server2ClientSD);
					c2s.setName("SD-" + Calendar.getInstance().getTimeInMillis());
					s2c.setName("SD-" + Calendar.getInstance().getTimeInMillis());
					c2s.start();
					s2c.start();
					threads.add(c2s);
					threads.add(s2c);
					// }

				} catch (ConnectException e) {
					String message = e.getMessage();
					System.out.println(e.getMessage());
					e.printStackTrace();
					if (message.equals("Connection refused"))
						Callbacks.printOutput(
								"Error: Connection Refused to " + this.ServerAddress + ":" + this.ServerPort);
					else
						Callbacks.printOutput(e.getMessage());
					connectionSocket.close();
					break;
				} catch(SSLHandshakeException e){
					connectionSocket.close();
					e.printStackTrace();
				}catch (Exception e){
					String message = e.getMessage();
					System.out.println(e.getMessage());
					e.printStackTrace();
					if (message.equals("Connection refused"))
						Callbacks.printOutput(
								"Error: Connection Refused to " + this.ServerAddress + ":" + this.ServerPort);
					else
						Callbacks.printOutput(e.getMessage());
					connectionSocket.close();
					break;

				}

			}
			connectionSocket.close();
		} catch (Exception ex) {
			Callbacks.printOutput(ex.getMessage());

		}
		Callbacks.printOutput("Main Thread Has Died but thats ok.");
		isRunning = false;

	}

	// TODO: add ports to test for ephemeral ports.
	public void repeatToServer(byte[] repeat, int srcPort) {
		System.out.println("There are " + pairs.size() + " Threads for this connection");
		SendData LastAccessed = null;

		for (SendData sd : pairs.keySet()) {
			if (LastAccessed == null || LastAccessed.createTime < sd.createTime) {
				LastAccessed = sd;
			}
		}
		if (LastAccessed != null) {
			LastAccessed.repeatRequest(repeat);
		} else {
			System.out.println("All Connections closed...");
		}

	}

	// TODO: add ports to test for ephemeral ports.
	public void repeatToClient(byte[] repeat, int srcPort) {
		System.out.println("There are " + pairs.size() + " Threads for this connection");
		SendData LastAccessed = null;
		for (SendData sd : pairs.values()) {
			if (LastAccessed == null || LastAccessed.createTime < sd.createTime) {
				LastAccessed = sd;
			}
		}

		if (LastAccessed != null) {
			LastAccessed.repeatRequest(repeat);
		} else {
			System.out.println("All Connections closed...");
		}
	}

	public boolean isRunning() {
		return this.isRunning;
	}

	public void setIntercept(boolean set) {
		this.isInterceptOn = set;
	}

	public boolean isInterceptOn() {
		return this.isInterceptOn;
	}

	public void setInterceptDir(int direction) {
		this.IntercetpDirection = direction;
	}

	public int getIntercetpDir() {
		return this.IntercetpDirection;
	}

	public void forwardC2SRequest(byte[] bytes) {
		// System.out.println("Forwarding Request...");
		interceptc2s.setData(bytes);
	}

	public void forwardS2CRequest(byte[] bytes) {
		// System.out.println("Forwarding Request...");
		intercepts2c.setData(bytes);
	}

	@Override
	public void DataReceived(ProxyEvent e) {
		NewDataEvent(e);

	}

	@Override
	public void Intercepted(ProxyEvent e, boolean isC2S) {
		InterceptedEvent(e, isC2S);

	}

	@Override
	public void PythonMessages(PythonOutputEvent e) {
		SendPyOutput(e);

	}

	private void KillSocks(SendData sd) {
		// System.out.println(sd.Name);
		try {
			if (sd.isSSL()) {
				/*
				 * ((SSLSocket)sd.sock).shutdownInput();
				 * ((SSLSocket)sd.sock).shutdownOutput();
				 */
				((SSLSocket) sd.sock).close();
			} else {
				/*
				 * ((Socket)sd.sock).shutdownInput();
				 * ((Socket)sd.sock).shutdownOutput();
				 */
				((Socket) sd.sock).close();
			}
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {

		}
	}

	@Override
	public void Closed(SendClosedEvent e) {
		synchronized (this) {
			Random rand = new Random();
			int to = rand.nextInt(1000);
			try {
				this.wait(to);
			} catch (InterruptedException e1) {
				e1.printStackTrace();
			}
			SendData tmp = (SendData) e.getSource();
			/*
			 * System.out.println(tmp.Name + ": Socket with " +
			 * ((SSLSocket)tmp.sock).getLocalPort() + " :: " +
			 * ((SSLSocket)tmp.sock).getPort());
			 * System.out.println("There are " + pairs.size() + " number of threads.");
			 * for(SendData s : pairs.keySet()){
			 * System.out.println("---"+s.Name + " :: " + ((SSLSocket)s.sock).getLocalPort()
			 * + " :: " + ((SSLSocket)s.sock).getPort());
			 * System.out.println("---"+s.doppel.Name + " :: " +
			 * ((SSLSocket)s.doppel.sock).getLocalPort() + " :: " +
			 * ((SSLSocket)s.doppel.sock).getPort());
			 * }
			 */

			if (pairs.containsKey(tmp)) {
				System.out.println("first");
				pairs.remove(tmp);
			} else if (pairs.containsValue(tmp)) {
			}
		}

	}
	

}
