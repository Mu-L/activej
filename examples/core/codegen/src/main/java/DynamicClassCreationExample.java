import io.activej.codegen.ClassBuilder;
import io.activej.codegen.DefiningClassLoader;
import io.activej.codegen.expression.ExpressionToString;
import org.jetbrains.annotations.NotNull;

import static io.activej.codegen.expression.Expressions.*;

/**
 * In this example a Class that implements the specified interface is dynamically created.
 * Methods are constructed programmatically using our fluent API built on top of ASM.
 */
public class DynamicClassCreationExample {
	public static final DefiningClassLoader CLASS_LOADER = DefiningClassLoader.create();

	public static void main(String[] args) throws ReflectiveOperationException {
		//[START REGION_2]
		// declare fields
		// setter for both fields - a sequence of actions
		// compareTo, equals, hashCode and toString methods implementations follow the standard convention
		Class<Person> personClass = ClassBuilder.create(Person.class)
				// declare fields
				.withField("id", int.class)
				.withField("name", String.class)

				// setter for both fields - a sequence of actions
				.withMethod("setIdAndName", sequence(
						set(property(self(), "id"), arg(0)),
						set(property(self(), "name"), arg(1))))
				.withMethod("getId", property(self(), "id"))
				.withMethod("getName", property(self(), "name"))

				// compareTo, equals, hashCode and toString methods implementations follow the standard convention
				.withMethod("int compareTo(Person)", compareToImpl("id", "name"))
				.withMethod("equals", equalsImpl("id", "name"))
				.withMethod("hashOfPojo", hash(property(arg(0), "id"), property(arg(0), "name")))
				.withMethod("hash", hash(property(self(), "id"), property(self(), "name")))
				.withMethod("toString", ExpressionToString.create()
						.withQuotes("{", "}", ", ")
						.with("id: ", property(self(), "id"))
						.with("name: ", property(self(), "name")))
				.defineClass(CLASS_LOADER);
		//[END REGION_2]

		//[START REGION_3]
		// Instantiate two objects of dynamically defined class
		Person jack = personClass.getDeclaredConstructor().newInstance();
		Person martha = personClass.getDeclaredConstructor().newInstance();

		jack.setIdAndName(5, "Jack");
		martha.setIdAndName(jack.getId() * 2, "Martha");

		System.out.println("First person: " + jack);
		System.out.println("Second person: " + martha);

		System.out.println("jack.equals(martha) ? : " + jack.equals(martha));
		//[END REGION_3]

		// Compare dynamically created hashing implementation with the conventional one
		ExamplePojo examplePojo = new ExamplePojo(5, "Jack");
		System.out.println(examplePojo);
		System.out.println("jack.hash(examplePojo)  = " + jack.hashOfPojo(examplePojo));
		System.out.println("jack.hash()             = " + jack.hash());
		System.out.println("examplePojo.hashCode()  = " + examplePojo.hashCode());
	}

	//[START REGION_1]
	@SuppressWarnings("unused")
	public interface Person extends Comparable<Person> {
		void setIdAndName(int id, String name);

		int getId();

		String getName();

		int hashOfPojo(ExamplePojo personPojo);

		int hash();

		@Override
		int compareTo(@NotNull Person o);

		@Override
		String toString();

		@Override
		boolean equals(Object obj);
	}
	//[END REGION_1]

	@SuppressWarnings("unused")
	public static class ExamplePojo {
		final int id;
		final String name;

		ExamplePojo(int id, String name) {
			this.id = id;
			this.name = name;
		}

		public int getId() {
			return id;
		}

		public String getName() {
			return name;
		}

		@Override
		public int hashCode() {
			int result = id;
			result = 31 * result + name.hashCode();
			return result;
		}

		@Override
		public String toString() {
			return "PersonPojo{id=" + id + ", name=" + name + '}';
		}
	}
}
