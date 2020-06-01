import io.activej.di.annotation.Inject;
import io.activej.di.module.Module;
import io.activej.eventloop.Eventloop;
import io.activej.launcher.Launcher;
import io.activej.rpc.client.RpcClient;
import io.activej.service.ServiceGraphModule;

import java.util.concurrent.CompletableFuture;

import static io.activej.di.module.Modules.combine;

// [START EXAMPLE]
public class ClientLauncher extends Launcher {
	private static final int TIMEOUT = 1000;

	@Inject
	private RpcClient client;

	@Inject
	Eventloop eventloop;

	@Override
	protected Module getModule() {
		return combine(
				ServiceGraphModule.create(),
				new ClientModule());
	}

	@Override
	protected void run() throws Exception {
		if (args.length < 2) {
			System.err.println("Command line args:\n\t--put key value\n\t--get key");
			return;
		}

		switch (args[0]) {
			case "--put":
				CompletableFuture<PutResponse> future1 = eventloop.submit(() ->
						client.sendRequest(new PutRequest(args[1], args[2]), TIMEOUT)
				);
				PutResponse putResponse = future1.get();
				System.out.println("PutResponse: " + putResponse);
				break;
			case "--get":
				CompletableFuture<GetResponse> future2 = eventloop.submit(() ->
						client.sendRequest(new GetRequest(args[1]), TIMEOUT)
				);
				GetResponse getResponse = future2.get();
				System.out.println("GetResponse: " + getResponse);
				break;
			default:
				throw new RuntimeException("Unsupported option: " + args[0]);
		}
	}

	public static void main(String[] args) throws Exception {
		ClientLauncher launcher = new ClientLauncher();
		launcher.launch(args);
	}
}
// [END EXAMPLE]
