import kg from 'urbit-key-generation';

const handler = async (event) => {

  const { ship, ticket } = JSON.parse(event.body);

  const wallet = await kg.generateWallet({ship: ship, ticket: ticket, boot: true});

  const keyFile = kg.generateKeyfile(wallet.network.keys, ship, 1);
  const code = kg.generateCode(wallet.network.keys);

  return {
    statusCode: 200,
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({key: keyFile, code: code})
  };
};

export {handler};
